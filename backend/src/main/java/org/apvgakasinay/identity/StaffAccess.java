package org.apvgakasinay.identity;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.apvgakasinay.identity.internal.Account;
import org.apvgakasinay.identity.internal.AccountRepository;
import org.apvgakasinay.identity.internal.Permissions;
import org.apvgakasinay.shared.ApiCaller;
import org.apvgakasinay.shared.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What another module may ask about a signed-in account.
 *
 * <p>{@link #require} is the rule of identity-and-security.md 6.3 for operations outside this
 * module: inside the operation's own transaction, the account row is read again under a shared
 * lock. A disablement, a demotion, a recovery or a password change holds that row exclusively,
 * so the operation either committed before the change or is refused here; it can never commit
 * afterwards.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class StaffAccess {

    /** An account that acted, as review screens and histories name it. Never an email address. */
    public record Actor(long id, String publicId, String displayName) {
    }

    private final AccountRepository accounts;

    public StaffAccess(AccountRepository accounts) {
        this.accounts = accounts;
    }

    /**
     * Re-reads the caller's account under a shared lock, held until the caller's transaction
     * ends, and returns it when it is still enabled, unchanged since the session was
     * established, and still holds the permission.
     */
    public Actor require(ApiCaller caller, String permission) {
        Account account = caller.signedIn() ? accounts.findByIdForShare(caller.accountId()).orElse(null) : null;
        if (account == null || !account.enabled() || account.securityVersion() != caller.securityVersion()) {
            // The session this request arrived with no longer stands for anyone.
            throw ApiException.of(HttpStatus.UNAUTHORIZED, "unauthenticated", "Sign-in required");
        }
        if (!Permissions.operational(account.role(), account.canDisburse()).contains(permission)) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "forbidden", "Not allowed for this account");
        }
        return new Actor(account.id(), account.publicId(), account.displayName());
    }

    /** The accounts with these identifiers, for naming who acted in a review history. */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public Map<Long, Actor> actors(Collection<Long> accountIds) {
        Map<Long, Actor> found = new HashMap<>();
        for (Long id : accountIds) {
            if (id != null && !found.containsKey(id)) {
                accounts.findById(id).ifPresent(account -> found.put(id, new Actor(account.id(), account.publicId(), account.displayName())));
            }
        }
        return found;
    }
}
