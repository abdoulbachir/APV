package org.apvgakasinay.identity.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.apvgakasinay.audit.AuditService.Outcome;
import org.apvgakasinay.shared.ApiException;
import org.apvgakasinay.shared.AppClock;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sign-up, sign-in, sign-out, password change and setting a password with a one-time credential.
 *
 * Not transactional as a whole, on purpose. Hashing a password takes tens of milliseconds and
 * megabytes of memory; it is done while holding no database connection. Only the few statements
 * that must succeed or fail together run in a transaction, and rate-limit counters and audit
 * records are written outside it, so they are kept whatever the outcome and no request ever
 * needs two connections at once.
 */
@Component
public class IdentityService {

    static final Duration ENROLLMENT_WINDOW = Duration.ofMinutes(60);

    /** The outcome of a successful password check. */
    public record SignIn(SessionState state, Account account, Instant pendingExpiresAt) {
    }

    private final AccountRepository accounts;
    private final PasswordPolicy policy;
    private final PasswordHasher hasher;
    private final Throttle throttle;
    private final Keys keys;
    private final Sessions sessions;
    private final Audit audit;
    private final AppClock clock;
    private final TransactionTemplate transaction;

    public IdentityService(AccountRepository accounts, PasswordPolicy policy, PasswordHasher hasher, Throttle throttle, Keys keys,
                           Sessions sessions, Audit audit, AppClock clock, PlatformTransactionManager transactions) {
        this.accounts = accounts;
        this.policy = policy;
        this.hasher = hasher;
        this.throttle = throttle;
        this.keys = keys;
        this.sessions = sessions;
        this.audit = audit;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
    }

    static ApiException invalidCredentials() {
        return ApiException.of(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Invalid credentials");
    }

    static ApiException unauthenticated() {
        return ApiException.of(HttpStatus.UNAUTHORIZED, "unauthenticated", "Sign-in required");
    }

    static ApiException credentialInvalid() {
        return ApiException.of(HttpStatus.BAD_REQUEST, "credential_invalid", "Invalid or expired credential");
    }

    static List<String> context(String email, String displayName) {
        return List.of(email.substring(0, Math.max(0, email.indexOf('@'))), displayName);
    }

    /**
     * Sign-up. The same work is done and the same answer is given whether or not the address
     * already had an account: the password is always hashed, and an existing account is left
     * untouched.
     */
    public void register(String email, String password, String displayName, String locale, Caller caller) {
        String normalizedPassword = policy.accept("password", password, context(email, displayName));
        String hash = hasher.hash(normalizedPassword);
        Instant now = clock.now();
        Optional<Long> created = transaction.execute(status -> {
            Optional<Long> id = accounts.insertIfAbsent(Keys.accountPublicId(), email, email.toLowerCase(Locale.ROOT),
                    displayName, locale, "donor", true, now);
            id.ifPresent(accountId -> accounts.setPassword(accountId, hash, now));
            return id;
        });
        // Whether an account was created is recorded here and nowhere the requester can see.
        audit.event("account.sign_up", Outcome.SUCCESS, created.orElse(null), caller, Map.of("created", String.valueOf(created.isPresent())));
    }

    /**
     * Password sign-in. A wrong password, an unknown address, a disabled account and a staff
     * account that is not activated all end in the same refusal after the same work.
     */
    public SignIn login(String email, String password, HttpServletRequest request, HttpServletResponse response, Caller caller) {
        String normalizedEmail = email.toLowerCase(Locale.ROOT);
        String pair = normalizedEmail + "|" + caller.address();
        // The attempt is counted before the password is looked at.
        // The audit log names the address attempted by a keyed hash, never in clear.
        String attempted = java.util.HexFormat.of().formatHex(keys.throttleKeyHash("audit.email|" + normalizedEmail));
        refuseIfThrottled(Throttle.PASSWORD_BY_EMAIL_AND_ADDRESS, pair, null, caller, attempted);
        refuseIfThrottled(Throttle.PASSWORD_BY_ACCOUNT, normalizedEmail, null, caller, attempted);

        Instant now = clock.now();
        Account account = accounts.findByNormalizedEmail(normalizedEmail).orElse(null);
        String hash = account == null ? null : accounts.passwordHash(account.id()).orElse(null);
        String normalizedPassword = PasswordPolicy.normalize(password);
        boolean accepted = hasher.matches(normalizedPassword, hash);

        boolean hasFactor = false;
        if (accepted) {
            hasFactor = account.isStaff() && accounts.hasActiveTotp(account.id());
            if (!account.enabled() || !account.activated()) {
                accepted = false;
            } else if (account.isStaff() && !hasFactor && !account.enrollmentWindowOpen(now)) {
                // The enrollment window closed with no second factor: the account is not activated any more.
                long id = account.id();
                transaction.executeWithoutResult(status -> {
                    accounts.removePassword(id);
                    accounts.setActivation(id, false, null);
                });
                audit.event("account.enrollment_window_closed", Outcome.REFUSED, id, caller);
                accepted = false;
            }
        }
        if (!accepted) {
            audit.event("session.sign_in", Outcome.FAILURE, account == null ? null : account.id(), caller, Map.of("email_hash", attempted));
            throw invalidCredentials();
        }

        throttle.clear(Throttle.PASSWORD_BY_EMAIL_AND_ADDRESS, pair);
        throttle.forgive(Throttle.PASSWORD_BY_ACCOUNT, normalizedEmail);
        if (hasher.needsUpgrade(hash)) {
            accounts.setPassword(account.id(), hasher.hash(normalizedPassword), now);
        }

        SessionState state = !account.isStaff() ? SessionState.AUTHENTICATED
                : hasFactor ? SessionState.MFA_REQUIRED : SessionState.MFA_ENROLLMENT_REQUIRED;
        // A session that was waiting for a second factor is simply replaced by this sign-in.
        sessions.establish(request, response, account, state, now);
        audit.event("session.sign_in", Outcome.SUCCESS, account.id(), caller, Map.of("state", state.code(), "email_hash", attempted));
        return new SignIn(state, account, state.pending() ? now.plus(Sessions.PENDING) : null);
    }

    public void logout(HttpServletRequest request, Caller caller) {
        Sessions.Identity identity = Sessions.identity(request.getSession(false));
        Sessions.end(request);
        if (identity != null) {
            audit.event("session.sign_out", Outcome.SUCCESS, identity.accountId(), caller);
        }
    }

    /**
     * Changes the signed-in person's own password. Every other session of the account ends;
     * this one continues under a new identifier.
     */
    public void changePassword(Account current, String currentPassword, String newPassword,
                               HttpServletRequest request, HttpServletResponse response, Caller caller) {
        // Body validation comes before the current password is looked at.
        String normalizedNew = policy.accept("newPassword", newPassword, context(current.email(), current.displayName()));
        String counter = "account:" + current.id();
        refuseIfThrottled(Throttle.PASSWORD_BY_ACCOUNT, counter, current.id(), caller, null);

        String hash = accounts.passwordHash(current.id()).orElse(null);
        if (!hasher.matches(PasswordPolicy.normalize(currentPassword), hash)) {
            // About the secret only: the session stays exactly as it was.
            audit.event("account.password_change", Outcome.FAILURE, current.id(), caller);
            throw invalidCredentials();
        }
        throttle.forgive(Throttle.PASSWORD_BY_ACCOUNT, counter);
        if (hasher.matches(normalizedNew, hash)) {
            throw ApiException.invalid("newPassword", "same_as_current", "newPassword must differ from the current password");
        }
        String newHash = hasher.hash(normalizedNew);

        HttpSession session = request.getSession(false);
        Sessions.Identity identity = Sessions.identity(session);
        Instant now = clock.now();
        Account updated = transaction.execute(status -> {
            // The row lock makes this wait for, or be seen by, a change of the account made at the same moment.
            Account locked = accounts.findByIdForUpdate(current.id()).orElse(null);
            if (locked == null || !locked.enabled() || locked.securityVersion() != identity.securityVersion()) {
                return null;
            }
            accounts.setPassword(locked.id(), newHash, now);
            accounts.raiseSecurityVersion(locked.id());
            sessions.endAllOf(locked, session.getId());
            return accounts.findById(locked.id()).orElseThrow();
        });
        if (updated == null) {
            // The identity was revoked while this request was running.
            Sessions.end(request);
            throw unauthenticated();
        }
        sessions.establish(request, response, updated, SessionState.AUTHENTICATED, identity.signedInAt());
        audit.event("account.password_change", Outcome.SUCCESS, updated.id(), caller, Map.of("sessions_ended", "others"));
    }

    /**
     * Sets a password with a one-time credential issued by the operator. A wrong, malformed,
     * expired or already used credential is refused the same way. It signs nobody in.
     */
    public void setPassword(String credential, String newPassword, Caller caller) {
        // Rules that need no knowledge of the account come first.
        policy.accept("newPassword", newPassword, List.of());
        Instant now = clock.now();
        byte[] credentialHash = Keys.sha256(credential);
        AccountRepository.Redeemed pending = accounts.peekCredential(credentialHash, now).orElse(null);
        Account account = pending == null ? null : accounts.findById(pending.accountId()).orElse(null);
        if (account == null || !account.enabled()) {
            audit.event("credential.redeem", Outcome.REFUSED, account == null ? null : account.id(), caller);
            throw credentialInvalid();
        }
        String normalized = policy.accept("newPassword", newPassword, context(account.email(), account.displayName()));
        String hash = hasher.hash(normalized);

        boolean redeemed = Boolean.TRUE.equals(transaction.execute(status -> {
            accounts.lockGuard();
            Account locked = accounts.findByIdForUpdate(account.id()).orElseThrow();
            // Used once: of two simultaneous redemptions only one changes the row.
            if (!locked.enabled() || accounts.redeemCredential(credentialHash, now).isEmpty()) {
                return false;
            }
            accounts.setPassword(locked.id(), hash, now);
            boolean needsEnrollment = locked.isStaff() && !accounts.hasActiveTotp(locked.id());
            // Only a credential opens the enrollment window; a password alone never does.
            accounts.setActivation(locked.id(), true, needsEnrollment ? now.plus(ENROLLMENT_WINDOW) : null);
            accounts.raiseSecurityVersion(locked.id());
            sessions.endAllOf(locked, null);
            return true;
        }));
        if (!redeemed) {
            audit.event("credential.redeem", Outcome.REFUSED, account.id(), caller);
            throw credentialInvalid();
        }
        audit.event("credential.redeem", Outcome.SUCCESS, account.id(), caller, Map.of("purpose", pending.purpose(), "sessions_ended", "all"));
    }

    private void refuseIfThrottled(Throttle.Limit limit, String key, Long accountId, Caller caller, String attempted) {
        long wait = throttle.count(limit, key);
        if (wait > 0) {
            audit.event("session.throttled", Outcome.REFUSED, accountId, caller,
                    attempted == null ? Map.of("limit", limit.scope()) : Map.of("limit", limit.scope(), "email_hash", attempted));
            throw Throttle.tooMany(wait);
        }
    }
}
