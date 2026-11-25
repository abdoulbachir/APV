package org.apvgakasinay.identity.internal.web;

import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.List;
import org.apvgakasinay.identity.internal.Account;
import org.apvgakasinay.identity.internal.AccountRepository;
import org.apvgakasinay.identity.internal.Sessions;
import org.apvgakasinay.shared.AppClock;
import org.apvgakasinay.shared.Timestamps;
import org.springframework.stereotype.Component;

/** Builds the private account view the signed-in person receives about themselves. */
@Component
class AccountViews {

    /** The permissions that work with the endpoints that exist in this milestone, for every role. */
    private static final List<String> OPERATIONAL_PERMISSIONS = List.of("account.read_own", "account.change_password");

    private final AccountRepository accounts;
    private final AppClock clock;

    AccountViews(AccountRepository accounts, AppClock clock) {
        this.accounts = accounts;
        this.clock = clock;
    }

    Responses.AccountView of(Account account, HttpSession session) {
        boolean enrolled = account.isStaff() && accounts.hasActiveTotp(account.id());
        Instant now = clock.now();
        Sessions.Identity identity = Sessions.identity(session);
        return new Responses.AccountView(
                account.publicId(),
                account.email(),
                account.emailVerified(),
                account.displayName(),
                account.role(),
                OPERATIONAL_PERMISSIONS,
                new Responses.Mfa(account.isStaff(), enrolled, enrolled ? accounts.recoveryCodesRemaining(account.id()) : null),
                account.locale(),
                Timestamps.format(account.createdAt()),
                new Responses.SessionInfo(
                        Timestamps.format(now.plusSeconds(session.getMaxInactiveInterval())),
                        Timestamps.format(identity.absoluteDeadline())));
    }
}
