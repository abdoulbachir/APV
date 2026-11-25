package org.apvgakasinay.identity.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.apvgakasinay.audit.AuditService.Outcome;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creating and recovering staff accounts (identity-and-security.md, section 8). Used by the
 * operator command; there is no network endpoint for any of it.
 *
 * Every method that can change who the usable super administrators are locks the guard row
 * first, so two such changes can never run at the same time, and then checks the invariant
 * while holding it. Nothing here ever handles a password: a person is given a one-time
 * credential and chooses their own.
 */
@Service
@Transactional
public class StaffAdministration {

    static final Duration ACTIVATION_VALIDITY = Duration.ofMinutes(60);
    static final Duration RESET_VALIDITY = Duration.ofMinutes(30);
    private static final Pattern EMAIL = Pattern.compile(
            "^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$");

    /** A refusal the operator must read. Never contains a secret. */
    public static class Refused extends RuntimeException {
        public Refused(String message) {
            super(message);
        }
    }

    private final AccountRepository accounts;
    private final SessionStore sessions;
    private final Audit audit;
    private final IdentityClock clock;

    public StaffAdministration(AccountRepository accounts, SessionStore sessions, Audit audit, IdentityClock clock) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.audit = audit;
        this.clock = clock;
    }

    /** Creates a staff account that is not activated, and returns its one-time activation credential. */
    public String createStaff(String email, String displayName, String role, String operator) {
        if (!role.equals("admin") && !role.equals("super_admin")) {
            throw new Refused("the role must be admin or super_admin");
        }
        if (email == null || email.length() > 254 || !EMAIL.matcher(email).matches()) {
            throw new Refused("that is not an email address this site accepts");
        }
        if (displayName == null || displayName.isBlank() || displayName.codePointCount(0, displayName.length()) > 80) {
            throw new Refused("the display name must be 1 to 80 characters");
        }
        accounts.lockGuard();
        Instant now = clock.now();
        long id = accounts.insertIfAbsent(Keys.accountPublicId(), email, email.toLowerCase(Locale.ROOT), displayName, "en", role, false, now)
                .orElseThrow(() -> new Refused("an account with that address already exists"));
        String credential = issue(id, "activation", ACTIVATION_VALIDITY, now);
        audit.operator("account.created", Outcome.SUCCESS, id, operator, Map.of("role", role));
        return credential;
    }

    /** Issues a password-reset credential. The second factor is not touched. */
    public String issuePasswordReset(String email, String operator, String identityCheck) {
        Account account = find(email);
        if (!account.enabled()) {
            throw new Refused("that account is disabled");
        }
        String credential = issue(account.id(), "password_reset", RESET_VALIDITY, clock.now());
        audit.operator("credential.issued", Outcome.SUCCESS, account.id(), operator, Map.of("purpose", "password_reset", "identity_check", identityCheck));
        return credential;
    }

    /**
     * For a staff member who lost their second factor. In one transaction: removes the factor
     * and its recovery codes, invalidates the current password, ends every session, cancels
     * every outstanding credential, and issues one fresh credential bound to the account.
     * Someone holding only the old password gains nothing.
     */
    public String recoverStaffAccess(String email, String operator, String identityCheck) {
        accounts.lockGuard();
        Account account = accounts.findByIdForUpdate(find(email).id()).orElseThrow();
        if (!account.isStaff()) {
            throw new Refused("staff recovery is for staff accounts; a donor is given a password reset");
        }
        Instant now = clock.now();
        accounts.removeSecondFactor(account.id());
        accounts.removePassword(account.id());
        accounts.setActivation(account.id(), false, null);
        accounts.raiseSecurityVersion(account.id());
        keepASuperAdministrator(account, operator, "staff_recovery");
        sessions.endAllOf(account, null);
        String credential = issue(account.id(), "staff_recovery", ACTIVATION_VALIDITY, now);
        audit.operator("account.staff_recovery", Outcome.SUCCESS, account.id(), operator, Map.of("identity_check", identityCheck));
        return credential;
    }

    public void setEnabled(String email, boolean enabled, String operator) {
        accounts.lockGuard();
        Account account = accounts.findByIdForUpdate(find(email).id()).orElseThrow();
        accounts.setEnabled(account.id(), enabled);
        accounts.raiseSecurityVersion(account.id());
        if (!enabled) {
            keepASuperAdministrator(account, operator, "disable");
            accounts.cancelCredentials(account.id(), clock.now());
        }
        sessions.endAllOf(account, null);
        audit.operator(enabled ? "account.enabled" : "account.disabled", Outcome.SUCCESS, account.id(), operator, Map.of());
    }

    public void setCanDisburse(String email, boolean canDisburse, String operator) {
        accounts.lockGuard();
        Account account = accounts.findByIdForUpdate(find(email).id()).orElseThrow();
        if (!account.role().equals("admin")) {
            throw new Refused("the disbursement permission is set on administrators only; a super administrator always has it");
        }
        accounts.setCanDisburse(account.id(), canDisburse);
        accounts.raiseSecurityVersion(account.id());
        sessions.endAllOf(account, null);
        audit.operator("account.can_disburse_changed", Outcome.SUCCESS, account.id(), operator,
                Map.of("old", String.valueOf(account.canDisburse()), "new", String.valueOf(canDisburse)));
    }

    @Transactional(readOnly = true)
    public List<Account> listStaff() {
        return accounts.findStaff();
    }

    private Account find(String email) {
        return accounts.findByNormalizedEmail(String.valueOf(email).toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new Refused("no account has that address"));
    }

    private String issue(long accountId, String purpose, Duration validity, Instant now) {
        String credential = Keys.oneTimeCredential();
        accounts.issueCredential(accountId, purpose, Keys.sha256(credential), now, now.plus(validity));
        return credential;
    }

    /** Called while holding the guard, after a change that may have removed a usable super administrator. */
    private void keepASuperAdministrator(Account account, String operator, String action) {
        if (accounts.guard().bootstrapped() && accounts.usableSuperAdministrators() == 0) {
            audit.operator("account.last_super_administrator_refused", Outcome.REFUSED, account.id(), operator, Map.of("action", action));
            throw new Refused("refused: this would leave no usable super administrator. Create and enroll another one first.");
        }
    }
}
