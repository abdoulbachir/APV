package org.apvgakasinay.identity;

import org.apvgakasinay.identity.internal.AccountRepository;
import org.springframework.stereotype.Component;

/**
 * Whether this database may receive fictional data (identity-and-security.md, 10.2).
 *
 * A database is disposable only when it was explicitly marked so when it was created, by the
 * schema {@code apv_disposable_database}: the local Compose database's init script and the
 * test setup create it; no migration and no application start-up ever does. And it stops
 * being disposable for good the first time the operator command is used on it.
 */
@Component
public class DatabasePurpose {

    private final AccountRepository accounts;

    public DatabasePurpose(AccountRepository accounts) {
        this.accounts = accounts;
    }

    /** True when fixtures and development accounts may be loaded. An unmarked database, empty or not, is not disposable. */
    public boolean disposable() {
        return accounts.disposableMarkerPresent() && !accounts.guard().databaseReal();
    }
}
