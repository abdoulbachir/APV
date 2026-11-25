package org.apvgakasinay.identity.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apvgakasinay.audit.AuditService.Outcome;
import org.apvgakasinay.shared.ApiException;
import org.apvgakasinay.shared.AppClock;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The second factor for staff: verification, enrollment, and recovery codes.
 *
 * Like {@link IdentityService}, only the statements that must succeed together run in a
 * transaction. Accepting a code is a single conditional update, atomic by itself.
 */
@Component
public class MfaService {

    static final int RECOVERY_CODES = 10;

    /** A pending authenticator secret, shown once. */
    public record Enrollment(String secret, String otpauthUri, Instant expiresAt) {

        @Override
        public String toString() {
            return "Enrollment[not shown]";
        }
    }

    private final AccountRepository accounts;
    private final Keys keys;
    private final PasswordHasher hasher;
    private final Throttle throttle;
    private final Sessions sessions;
    private final Audit audit;
    private final AppClock clock;
    private final TransactionTemplate transaction;

    public MfaService(AccountRepository accounts, Keys keys, PasswordHasher hasher, Throttle throttle, Sessions sessions,
                      Audit audit, AppClock clock, PlatformTransactionManager transactions) {
        this.accounts = accounts;
        this.keys = keys;
        this.hasher = hasher;
        this.throttle = throttle;
        this.sessions = sessions;
        this.audit = audit;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
    }

    static ApiException codeInvalid() {
        return ApiException.of(HttpStatus.UNAUTHORIZED, "mfa_code_invalid", "Invalid code");
    }

    private static String counter(Account account) {
        return "account:" + account.id();
    }

    /** How one attempt on a pending sign-in ended inside its transaction. */
    private enum Step { ACCEPTED, WRONG_CODE, EXHAUSTED, ALREADY_COMPLETED, ENDED, REVOKED, WINDOW_CLOSED, LOST_RACE, FORBIDDEN }

    private record Attempt(Step step, Account account, List<String> codes, boolean bootstrapped, Enrollment enrollment) {

        @Override
        public String toString() {
            return "Attempt[" + step + "]";
        }

        static Attempt of(Step step) {
            return new Attempt(step, null, null, false, null);
        }
    }

    /**
     * The first thing every attempt on a pending sign-in does, inside its transaction: lock the
     * pending row, then read the account under a lock and compare it with what the session was
     * issued for. Requests of one pending sign-in therefore run one after another on any
     * instance, and a request that began before the account was disabled, recovered or given a
     * new password finds that out here, before it checks a code or changes anything.
     *
     * Lock order, everywhere: pending sign-in, then the guard, then the account. The operator's
     * changes take the guard and then the account, and never a pending row, so no cycle exists.
     *
     * @return the step that ends the attempt, or null to go on
     */
    private Step gate(byte[] pendingId, Instant now) {
        AccountRepository.Pending pending = pendingId == null ? null : accounts.lockPending(pendingId).orElse(null);
        if (pending != null && pending.completed()) {
            return Step.ALREADY_COMPLETED;
        }
        if (pending == null || !pending.open(now)) {
            return Step.ENDED;
        }
        return null;
    }

    /** True when the account is no longer the one this session was issued for. */
    private static boolean stale(Account account, Sessions.Identity identity) {
        return account == null || identity == null || !account.enabled() || account.securityVersion() != identity.securityVersion();
    }

    /** Counts a wrong code against the pending sign-in. Called while holding its row lock. */
    private Step wrong(byte[] pendingId) {
        return accounts.countPendingFailure(pendingId) >= 5 ? Step.EXHAUSTED : Step.WRONG_CODE;
    }

    /** Turns the ways an attempt can end without success into the contract's answers. */
    private ApiException refusal(Step step, Account account, String event, String method, HttpServletRequest request, Caller caller) {
        switch (step) {
            case ALREADY_COMPLETED:
                // The same pending sign-in was completed by a request that ran just before this one.
                return ApiException.of(HttpStatus.CONFLICT, "already_signed_in", "Already signed in");
            case WRONG_CODE:
                audit.event(event, Outcome.FAILURE, account.id(), caller, Map.of("method", method));
                return codeInvalid();
            case EXHAUSTED:
                Sessions.end(request);
                audit.event(event, Outcome.REFUSED, account.id(), caller, Map.of("method", method, "reason", "attempts_exhausted"));
                return IdentityService.unauthenticated();
            case WINDOW_CLOSED:
                Sessions.end(request);
                audit.event(event, Outcome.REFUSED, account.id(), caller, Map.of("reason", "window_closed"));
                return IdentityService.unauthenticated();
            case REVOKED:
                Sessions.end(request);
                audit.event("session.revoked", Outcome.REFUSED, account.id(), caller, Map.of("during", event));
                return IdentityService.unauthenticated();
            case FORBIDDEN:
                return ApiException.of(HttpStatus.FORBIDDEN, "forbidden", "Not allowed in this session state");
            default:
                // The pending sign-in is over: expired, or ended by its fifth wrong code.
                Sessions.end(request);
                return IdentityService.unauthenticated();
        }
    }

    /** Completes a pending sign-in with an authenticator code or a recovery code. */
    public Account verify(Account current, boolean recoveryCode, String code, HttpServletRequest request,
                          HttpServletResponse response, Caller caller) {
        refuseIfThrottled(current, caller);
        Instant now = clock.now();
        HttpSession session = request.getSession(false);
        Sessions.Identity identity = Sessions.identity(session);
        byte[] pendingId = Sessions.pendingId(session);
        String method = recoveryCode ? "recovery_code" : "totp";

        Attempt result = transaction.execute(status -> {
            Step ended = gate(pendingId, now);
            if (ended != null) {
                return Attempt.of(ended);
            }
            // Shared: a disablement, recovery or password change of this account holds the row
            // exclusively, so this either sees it or finishes before it.
            Account account = accounts.findByIdForShare(current.id()).orElse(null);
            if (stale(account, identity)) {
                return Attempt.of(Step.REVOKED);
            }
            boolean accepted = recoveryCode
                    ? accounts.consumeRecoveryCode(account.id(), keys.recoveryCodeHash(code), now)
                    : acceptTotp(account, code, now);
            if (!accepted) {
                return Attempt.of(wrong(pendingId));
            }
            accounts.completePending(pendingId);
            return new Attempt(Step.ACCEPTED, account, null, false, null);
        });
        if (result.step() != Step.ACCEPTED) {
            throw refusal(result.step(), current, "session.second_factor", method, request, caller);
        }
        throttle.forgive(Throttle.SECOND_FACTOR_BY_ACCOUNT, counter(current));
        // The account as it was read under the lock: the session stores that security version.
        sessions.establish(request, response, result.account(), SessionState.AUTHENTICATED, identity.signedInAt());
        audit.event("session.second_factor", Outcome.SUCCESS, current.id(), caller, Map.of("method", method));
        return result.account();
    }

    /** Starts enrollment: stores a pending secret, replacing an earlier pending one, and returns it once. */
    public Enrollment enroll(Account current, HttpServletRequest request, Caller caller) {
        Instant now = clock.now();
        HttpSession session = request.getSession(false);
        Sessions.Identity identity = Sessions.identity(session);
        byte[] pendingId = Sessions.pendingId(session);
        byte[] secret = Keys.totpSecret();
        byte[] encrypted = keys.encrypt(secret);
        Instant expiresAt = now.plus(Sessions.PENDING);

        Attempt result = transaction.execute(status -> {
            Step ended = gate(pendingId, now);
            if (ended != null) {
                return Attempt.of(ended);
            }
            Account account = accounts.findByIdForShare(current.id()).orElse(null);
            if (stale(account, identity)) {
                return Attempt.of(Step.REVOKED);
            }
            if (accounts.hasActiveTotp(account.id())) {
                // A second factor became active meanwhile; this pending session has nothing left to do.
                return Attempt.of(Step.FORBIDDEN);
            }
            if (!account.enrollmentWindowOpen(now)) {
                return Attempt.of(Step.WINDOW_CLOSED);
            }
            if (!accounts.storePendingTotp(account.id(), encrypted, expiresAt)) {
                return Attempt.of(Step.FORBIDDEN);
            }
            return new Attempt(Step.ACCEPTED, account, null, false, null);
        });
        if (result.step() != Step.ACCEPTED) {
            throw refusal(result.step(), current, "mfa.enroll", "totp", request, caller);
        }
        audit.event("mfa.enroll", Outcome.SUCCESS, current.id(), caller);
        String base32 = Keys.base32(secret);
        return new Enrollment(base32, Totp.otpauthUri(base32, result.account().email()), expiresAt);
    }

    /**
     * Finishes enrollment. Exactly one of several simultaneous confirmations succeeds; the
     * others get no codes, and their answer depends only on what they presented.
     */
    public List<String> confirm(Account current, String code, HttpServletRequest request, HttpServletResponse response, Caller caller) {
        refuseIfThrottled(current, caller);
        Instant now = clock.now();
        HttpSession session = request.getSession(false);
        Sessions.Identity identity = Sessions.identity(session);
        byte[] pendingId = Sessions.pendingId(session);
        byte[] sessionHash = Keys.sha256(session.getId());

        Attempt result = transaction.execute(status -> {
            Step ended = gate(pendingId, now);
            if (ended != null) {
                return Attempt.of(ended);
            }
            // Completing the first enrollment can bootstrap the site, so the guard comes before the account.
            AccountRepository.Guard guard = accounts.lockGuard();
            Account account = accounts.findByIdForUpdate(current.id()).orElse(null);
            if (stale(account, identity)) {
                return Attempt.of(Step.REVOKED);
            }
            AccountRepository.TotpRow row = accounts.totp(account.id()).orElse(null);
            if (row != null && row.active()) {
                // Another pending sign-in of this account enrolled first.
                return Attempt.of(Step.LOST_RACE);
            }
            if (!account.enrollmentWindowOpen(now)) {
                return Attempt.of(Step.WINDOW_CLOSED);
            }
            long step = row == null || !now.isBefore(row.pendingExpiresAt())
                    ? -1 : Totp.matchingStep(keys.decrypt(row.secretEncrypted()), code, now);
            if (step < 0) {
                return Attempt.of(wrong(pendingId));
            }
            if (!accounts.activatePendingTotp(account.id(), row.secretEncrypted(), step, now, sessionHash)) {
                return Attempt.of(Step.LOST_RACE);
            }
            List<String> codes = newRecoveryCodes(account);
            accounts.setActivation(account.id(), true, null);
            boolean bootstrapped = !guard.bootstrapped() && accounts.usableSuperAdministrators() > 0;
            if (bootstrapped) {
                accounts.markBootstrapped();
            }
            accounts.completePending(pendingId);
            // Read again under the same lock: this is the account the new session is issued for.
            return new Attempt(Step.ACCEPTED, accounts.findById(account.id()).orElseThrow(), codes, bootstrapped, null);
        });

        if (result.step() == Step.LOST_RACE) {
            // Its code is no good: the factor that is active now is not the one this session was shown.
            audit.event("mfa.enroll_confirm", Outcome.FAILURE, current.id(), caller, Map.of("method", "totp", "reason", "already_enrolled"));
            throw codeInvalid();
        }
        if (result.step() != Step.ACCEPTED) {
            throw refusal(result.step(), current, "mfa.enroll_confirm", "totp", request, caller);
        }
        throttle.forgive(Throttle.SECOND_FACTOR_BY_ACCOUNT, counter(current));
        sessions.establish(request, response, result.account(), SessionState.AUTHENTICATED, identity.signedInAt());
        if (result.bootstrapped()) {
            audit.event("site.bootstrapped", Outcome.SUCCESS, current.id(), caller);
        }
        audit.event("mfa.enroll_confirm", Outcome.SUCCESS, current.id(), caller);
        return result.codes();
    }

    /**
     * Replaces all recovery codes. Needs the password and a fresh authenticator code, although
     * the session is signed in. The code is consumed and the codes are replaced in one
     * transaction, under the account's row lock, and only if the account, its security version
     * and the password that was just verified are all still current.
     */
    public List<String> regenerateRecoveryCodes(Account current, String currentPassword, String code, HttpServletRequest request, Caller caller) {
        Sessions.Identity identity = Sessions.identity(request.getSession(false));
        String passwordCounter = IdentityService.passwordCounter(current);
        long wait = throttle.count(Throttle.PASSWORD_BY_ACCOUNT, passwordCounter);
        if (wait > 0) {
            audit.event("session.throttled", Outcome.REFUSED, current.id(), caller, Map.of("limit", Throttle.PASSWORD_BY_ACCOUNT.scope()));
            throw Throttle.tooMany(wait);
        }
        String hash = accounts.passwordHash(current.id()).orElse(null);
        if (!hasher.matches(PasswordPolicy.normalize(currentPassword), hash)) {
            audit.event("mfa.recovery_codes", Outcome.FAILURE, current.id(), caller, Map.of("reason", "password"));
            throw IdentityService.invalidCredentials();
        }
        throttle.forgive(Throttle.PASSWORD_BY_ACCOUNT, passwordCounter);
        refuseIfThrottled(current, caller);
        Instant now = clock.now();

        Attempt result = transaction.execute(status -> {
            Account account = accounts.findByIdForUpdate(current.id()).orElse(null);
            if (stale(account, identity) || !hash.equals(accounts.passwordHash(account.id()).orElse(null))) {
                return Attempt.of(Step.REVOKED);
            }
            // A recovery code cannot be used here: only a code from the authenticator application.
            if (!acceptTotp(account, code, now)) {
                return Attempt.of(Step.WRONG_CODE);
            }
            return new Attempt(Step.ACCEPTED, account, newRecoveryCodes(account), false, null);
        });
        if (result.step() == Step.WRONG_CODE) {
            // About the code only: this is a signed-in session, and it stays exactly as it was.
            audit.event("mfa.recovery_codes", Outcome.FAILURE, current.id(), caller, Map.of("reason", "code"));
            throw codeInvalid();
        }
        if (result.step() != Step.ACCEPTED) {
            throw refusal(result.step(), current, "mfa.recovery_codes", "totp", request, caller);
        }
        throttle.forgive(Throttle.SECOND_FACTOR_BY_ACCOUNT, counter(current));
        audit.event("mfa.recovery_codes", Outcome.SUCCESS, current.id(), caller);
        return result.codes();
    }

    private boolean acceptTotp(Account account, String code, Instant now) {
        AccountRepository.TotpRow row = accounts.totp(account.id()).orElse(null);
        if (row == null || !row.active()) {
            return false;
        }
        long step = Totp.matchingStep(keys.decrypt(row.secretEncrypted()), code, now);
        // The conditional update is what makes a code work once, even sent twice at the same instant.
        return step >= 0 && accounts.acceptStep(account.id(), step);
    }

    private List<String> newRecoveryCodes(Account account) {
        List<String> codes = new ArrayList<>();
        List<byte[]> hashes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            String code = Keys.recoveryCode();
            codes.add(code);
            hashes.add(keys.recoveryCodeHash(code));
        }
        accounts.replaceRecoveryCodes(account.id(), hashes);
        return codes;
    }

    private void refuseIfThrottled(Account account, Caller caller) {
        long wait = throttle.count(Throttle.SECOND_FACTOR_BY_ACCOUNT, counter(account));
        if (wait > 0) {
            audit.event("session.throttled", Outcome.REFUSED, account.id(), caller, Map.of("limit", Throttle.SECOND_FACTOR_BY_ACCOUNT.scope()));
            throw Throttle.tooMany(wait);
        }
    }
}
