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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The second factor for staff: verification, enrollment, and recovery codes. */
@Service
@Transactional
public class MfaService {

    static final int RECOVERY_CODES = 10;

    /** A pending authenticator secret, shown once. */
    public record Enrollment(String secret, String otpauthUri, Instant expiresAt) {
    }

    private final AccountRepository accounts;
    private final Keys keys;
    private final PasswordHasher hasher;
    private final Throttle throttle;
    private final Sessions sessions;
    private final Audit audit;
    private final AppClock clock;

    public MfaService(AccountRepository accounts, Keys keys, PasswordHasher hasher, Throttle throttle, Sessions sessions,
                      Audit audit, AppClock clock) {
        this.accounts = accounts;
        this.keys = keys;
        this.hasher = hasher;
        this.throttle = throttle;
        this.sessions = sessions;
        this.audit = audit;
        this.clock = clock;
    }

    static ApiException codeInvalid() {
        return ApiException.of(HttpStatus.UNAUTHORIZED, "mfa_code_invalid", "Invalid code");
    }

    private static String counter(Account account) {
        return "account:" + account.id();
    }

    /** Completes a pending sign-in with an authenticator code or a recovery code. */
    public Account verify(Account account, boolean recoveryCode, String code, HttpServletRequest request,
                          HttpServletResponse response, Caller caller) {
        refuseIfThrottled(account, caller);
        Instant now = clock.now();
        boolean accepted = recoveryCode
                ? accounts.consumeRecoveryCode(account.id(), keys.recoveryCodeHash(code), now)
                : acceptTotp(account, code, now);
        String method = recoveryCode ? "recovery_code" : "totp";
        if (!accepted) {
            wrongCode(request, account, "session.second_factor", method, caller);
        }
        throttle.forgive(Throttle.SECOND_FACTOR_BY_ACCOUNT, counter(account));
        Instant signedInAt = Sessions.identity(request.getSession(false)).signedInAt();
        sessions.establish(request, response, account, SessionState.AUTHENTICATED, signedInAt);
        audit.event("session.second_factor", Outcome.SUCCESS, account.id(), caller, Map.of("method", method));
        return account;
    }

    /** Starts enrollment: stores a pending secret, replacing an earlier pending one, and returns it once. */
    public Enrollment enroll(Account current, HttpServletRequest request, Caller caller) {
        Instant now = clock.now();
        Account account = accounts.findByIdForUpdate(current.id()).orElseThrow(IdentityService::unauthenticated);
        if (!account.enrollmentWindowOpen(now)) {
            Sessions.end(request);
            audit.event("mfa.enroll", Outcome.REFUSED, account.id(), caller, Map.of("reason", "window_closed"));
            throw IdentityService.unauthenticated();
        }
        byte[] secret = Keys.totpSecret();
        Instant expiresAt = now.plus(Sessions.PENDING);
        if (!accounts.storePendingTotp(account.id(), keys.encrypt(secret), expiresAt)) {
            // A second factor became active meanwhile; this pending session has nothing left to do.
            throw ApiException.of(HttpStatus.FORBIDDEN, "forbidden", "Not allowed in this session state");
        }
        audit.event("mfa.enroll", Outcome.SUCCESS, account.id(), caller);
        String base32 = Keys.base32(secret);
        return new Enrollment(base32, Totp.otpauthUri(base32, account.email()), expiresAt);
    }

    /**
     * Finishes enrollment. Exactly one of several simultaneous confirmations succeeds; the
     * others get no codes, and their answer depends only on what they presented.
     *
     * Kept on refusal: the count of wrong codes lives in the session, not in this transaction.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public List<String> confirm(Account current, String code, HttpServletRequest request, HttpServletResponse response, Caller caller) {
        Instant now = clock.now();
        // Completing the first enrollment can bootstrap the site, so the guard comes first.
        AccountRepository.Guard guard = accounts.lockGuard();
        Account account = accounts.findByIdForUpdate(current.id()).orElseThrow(IdentityService::unauthenticated);
        AccountRepository.TotpRow row = accounts.totp(account.id()).orElse(null);
        if (row != null && row.active()) {
            throw lostRace(request);
        }
        if (!account.enrollmentWindowOpen(now)) {
            Sessions.end(request);
            audit.event("mfa.enroll_confirm", Outcome.REFUSED, account.id(), caller, Map.of("reason", "window_closed"));
            throw IdentityService.unauthenticated();
        }
        refuseIfThrottled(account, caller);
        long step = row == null || !now.isBefore(row.pendingExpiresAt()) ? -1 : Totp.matchingStep(keys.decrypt(row.secretEncrypted()), code, now);
        if (step < 0) {
            wrongCode(request, account, "mfa.enroll_confirm", "totp", caller);
        }
        if (!accounts.activatePendingTotp(account.id(), row.secretEncrypted(), step, now)) {
            throw lostRace(request);
        }
        throttle.forgive(Throttle.SECOND_FACTOR_BY_ACCOUNT, counter(account));

        List<String> codes = newRecoveryCodes(account);
        accounts.setActivation(account.id(), true, null);
        if (!guard.bootstrapped() && accounts.usableSuperAdministrators() > 0) {
            accounts.markBootstrapped();
            audit.event("site.bootstrapped", Outcome.SUCCESS, account.id(), caller);
        }
        Instant signedInAt = Sessions.identity(request.getSession(false)).signedInAt();
        sessions.establish(request, response, account, SessionState.AUTHENTICATED, signedInAt);
        audit.event("mfa.enroll_confirm", Outcome.SUCCESS, account.id(), caller);
        return codes;
    }

    /** Replaces all recovery codes. Needs the password and a fresh authenticator code, although the session is signed in. */
    public List<String> regenerateRecoveryCodes(Account account, String currentPassword, String code, Caller caller) {
        long wait = throttle.count(Throttle.PASSWORD_BY_ACCOUNT, counter(account));
        if (wait > 0) {
            throw Throttle.tooMany(wait);
        }
        String hash = accounts.passwordHash(account.id()).orElse(null);
        if (!hasher.matches(PasswordPolicy.normalize(currentPassword), hash)) {
            audit.event("mfa.recovery_codes", Outcome.FAILURE, account.id(), caller, Map.of("reason", "password"));
            throw IdentityService.invalidCredentials();
        }
        throttle.forgive(Throttle.PASSWORD_BY_ACCOUNT, counter(account));
        refuseIfThrottled(account, caller);
        // A recovery code cannot be used here: only a code from the authenticator application.
        if (!acceptTotp(account, code, clock.now())) {
            audit.event("mfa.recovery_codes", Outcome.FAILURE, account.id(), caller, Map.of("reason", "code"));
            throw codeInvalid();
        }
        throttle.forgive(Throttle.SECOND_FACTOR_BY_ACCOUNT, counter(account));
        List<String> codes = newRecoveryCodes(account);
        audit.event("mfa.recovery_codes", Outcome.SUCCESS, account.id(), caller);
        return codes;
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

    /** A wrong code keeps the pending session, except the fifth, which ends it. Always throws. */
    private void wrongCode(HttpServletRequest request, Account account, String event, String method, Caller caller) {
        HttpSession session = request.getSession(false);
        if (session != null && Sessions.countMfaFailure(session)) {
            Sessions.end(request);
            audit.event(event, Outcome.REFUSED, account.id(), caller, Map.of("method", method, "reason", "attempts_exhausted"));
            throw IdentityService.unauthenticated();
        }
        audit.event(event, Outcome.FAILURE, account.id(), caller, Map.of("method", method));
        throw codeInvalid();
    }

    /**
     * Another request activated the factor first. If this request's own session was the one
     * that got signed in (its identifier has been replaced), it is told so; a different
     * pending session of the same account is told its code is no good.
     */
    private ApiException lostRace(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        boolean sameSessionWasSignedIn = session == null || !sessions.exists(session.getId())
                || Sessions.identity(session) == null || Sessions.identity(session).state() == SessionState.AUTHENTICATED;
        return sameSessionWasSignedIn
                ? ApiException.of(HttpStatus.CONFLICT, "already_signed_in", "Already signed in")
                : codeInvalid();
    }

    private void refuseIfThrottled(Account account, Caller caller) {
        long wait = throttle.count(Throttle.SECOND_FACTOR_BY_ACCOUNT, counter(account));
        if (wait > 0) {
            audit.event("session.throttled", Outcome.REFUSED, account.id(), caller, Map.of("limit", Throttle.SECOND_FACTOR_BY_ACCOUNT.scope()));
            throw Throttle.tooMany(wait);
        }
    }
}
