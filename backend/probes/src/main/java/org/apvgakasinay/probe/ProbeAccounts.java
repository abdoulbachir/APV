package org.apvgakasinay.probe;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Three fictional in-memory accounts. The real implementation stores accounts in PostgreSQL. */
@Component
public class ProbeAccounts {

    public static final String DONOR = "donor@example.org";
    public static final String ADMIN = "admin@example.org";
    public static final String NEW_ADMIN = "new.admin@example.org";
    public static final String PASSWORD = "correct horse battery staple 42";

    public static final class Account {
        final String email;
        final String role;
        volatile String passwordHash;
        /** Null until the account has an active second factor. */
        volatile byte[] totpSecret;
        /** A secret waiting for confirmation, during enrollment. */
        volatile byte[] pendingTotpSecret;
        final AtomicLong lastAcceptedStep = new AtomicLong(-1);

        Account(String email, String role, String passwordHash, byte[] totpSecret) {
            this.email = email;
            this.role = role;
            this.passwordHash = passwordHash;
            this.totpSecret = totpSecret;
        }

        public boolean isStaff() {
            return !role.equals("donor");
        }

        public byte[] totpSecret() {
            return totpSecret;
        }

        public byte[] pendingTotpSecret() {
            return pendingTotpSecret;
        }
    }

    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    private final PasswordEncoder encoder;

    public ProbeAccounts(PasswordEncoder encoder) {
        this.encoder = encoder;
        reset();
    }

    /** Puts the accounts back to their starting state. Called by tests. */
    public void reset() {
        String hash = encoder.encode(PASSWORD);
        accounts.put(DONOR, new Account(DONOR, "donor", hash, null));
        accounts.put(ADMIN, new Account(ADMIN, "admin", hash, "12345678901234567890".getBytes(StandardCharsets.US_ASCII)));
        accounts.put(NEW_ADMIN, new Account(NEW_ADMIN, "admin", hash, null));
    }

    public Account find(String email) {
        return email == null ? null : accounts.get(email);
    }
}
