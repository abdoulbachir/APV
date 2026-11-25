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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sign-up, sign-in, sign-out, password change and setting a password with a one-time credential. */
@Service
@Transactional
public class IdentityService {

    static final Duration ENROLLMENT_WINDOW = Duration.ofMinutes(60);

    /** The outcome of a successful password check. */
    public record SignIn(SessionState state, Account account, Instant pendingExpiresAt) {
    }

    private final AccountRepository accounts;
    private final PasswordPolicy policy;
    private final PasswordHasher hasher;
    private final Throttle throttle;
    private final Sessions sessions;
    private final Audit audit;
    private final AppClock clock;

    public IdentityService(AccountRepository accounts, PasswordPolicy policy, PasswordHasher hasher, Throttle throttle,
                           Sessions sessions, Audit audit, AppClock clock) {
        this.accounts = accounts;
        this.policy = policy;
        this.hasher = hasher;
        this.throttle = throttle;
        this.sessions = sessions;
        this.audit = audit;
        this.clock = clock;
    }

    static ApiException invalidCredentials() {
        return ApiException.of(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Invalid credentials");
    }

    static ApiException unauthenticated() {
        return ApiException.of(HttpStatus.UNAUTHORIZED, "unauthenticated", "Sign-in required");
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
        Optional<Long> created = accounts.insertIfAbsent(Keys.accountPublicId(), email, email.toLowerCase(Locale.ROOT),
                displayName, locale, "donor", true, now);
        created.ifPresent(id -> accounts.setPassword(id, hash, now));
        // Whether an account was created is recorded here and nowhere the requester can see.
        audit.event("account.sign_up", Outcome.SUCCESS, created.orElse(null), caller, Map.of("created", String.valueOf(created.isPresent())));
    }

    /**
     * Password sign-in. A wrong password, an unknown address, a disabled account and a staff
     * account that is not activated all end in the same refusal after the same work.
     *
     * Changes made before a refusal (closing an expired enrollment window) are kept.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public SignIn login(String email, String password, HttpServletRequest request, HttpServletResponse response, Caller caller) {
        String normalizedEmail = email.toLowerCase(Locale.ROOT);
        String pair = normalizedEmail + "|" + caller.address();
        // The attempt is counted before the password is looked at.
        refuseIfThrottled(Throttle.PASSWORD_BY_EMAIL_AND_ADDRESS, pair, null, caller);
        refuseIfThrottled(Throttle.PASSWORD_BY_ACCOUNT, normalizedEmail, null, caller);

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
                accounts.removePassword(account.id());
                accounts.setActivation(account.id(), false, null);
                audit.event("account.enrollment_window_closed", Outcome.REFUSED, account.id(), caller);
                accepted = false;
            }
        }
        if (!accepted) {
            audit.event("session.sign_in", Outcome.FAILURE, account == null ? null : account.id(), caller);
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
        audit.event("session.sign_in", Outcome.SUCCESS, account.id(), caller, Map.of("state", state.code()));
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
        refuseIfThrottled(Throttle.PASSWORD_BY_ACCOUNT, counter, current.id(), caller);

        Account account = accounts.findByIdForUpdate(current.id()).orElseThrow(IdentityService::unauthenticated);
        String hash = accounts.passwordHash(account.id()).orElse(null);
        if (!hasher.matches(PasswordPolicy.normalize(currentPassword), hash)) {
            // About the secret only: the session stays exactly as it was.
            audit.event("account.password_change", Outcome.FAILURE, account.id(), caller);
            throw invalidCredentials();
        }
        throttle.forgive(Throttle.PASSWORD_BY_ACCOUNT, counter);
        if (hasher.matches(normalizedNew, hash)) {
            throw ApiException.invalid("newPassword", "same_as_current", "newPassword must differ from the current password");
        }

        Instant now = clock.now();
        accounts.setPassword(account.id(), hasher.hash(normalizedNew), now);
        accounts.raiseSecurityVersion(account.id());
        Account updated = accounts.findById(account.id()).orElseThrow();

        HttpSession session = request.getSession(false);
        Sessions.Identity identity = Sessions.identity(session);
        sessions.endAllOf(updated, session.getId());
        sessions.establish(request, response, updated, SessionState.AUTHENTICATED, identity.signedInAt());
        audit.event("account.password_change", Outcome.SUCCESS, account.id(), caller);
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
        if (pending == null) {
            audit.event("credential.redeem", Outcome.REFUSED, null, caller);
            throw credentialInvalid();
        }
        accounts.lockGuard();
        Account account = accounts.findByIdForUpdate(pending.accountId()).orElseThrow(IdentityService::credentialInvalid);
        if (!account.enabled()) {
            audit.event("credential.redeem", Outcome.REFUSED, account.id(), caller);
            throw credentialInvalid();
        }
        String normalized = policy.accept("newPassword", newPassword, context(account.email(), account.displayName()));
        String hash = hasher.hash(normalized);
        // Used once: of two simultaneous redemptions only one changes the row.
        if (accounts.redeemCredential(credentialHash, now).isEmpty()) {
            audit.event("credential.redeem", Outcome.REFUSED, account.id(), caller);
            throw credentialInvalid();
        }

        accounts.setPassword(account.id(), hash, now);
        boolean needsEnrollment = account.isStaff() && !accounts.hasActiveTotp(account.id());
        // Only a credential opens the enrollment window; a password alone never does.
        accounts.setActivation(account.id(), true, needsEnrollment ? now.plus(ENROLLMENT_WINDOW) : null);
        accounts.raiseSecurityVersion(account.id());
        sessions.endAllOf(account, null);
        audit.event("credential.redeem", Outcome.SUCCESS, account.id(), caller, Map.of("purpose", pending.purpose()));
    }

    static ApiException credentialInvalid() {
        return ApiException.of(HttpStatus.BAD_REQUEST, "credential_invalid", "Invalid or expired credential");
    }

    private void refuseIfThrottled(Throttle.Limit limit, String key, Long accountId, Caller caller) {
        long wait = throttle.count(limit, key);
        if (wait > 0) {
            audit.event("session.throttled", Outcome.REFUSED, accountId, caller, Map.of("limit", limit.scope()));
            throw Throttle.tooMany(wait);
        }
    }
}
