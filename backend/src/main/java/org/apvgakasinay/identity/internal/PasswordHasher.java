package org.apvgakasinay.identity.internal;

import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apvgakasinay.shared.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Argon2id through Spring Security's encoder, with two protections around it.
 *
 * Hashing costs memory, so only a few hashes run at once; a request that cannot start within
 * a short wait is told to come back, and the server's memory use stays bounded.
 *
 * Checking a password for an address with no account still does the work of a real check,
 * so an unknown address is not answered faster than a wrong password.
 */
@Component
public class PasswordHasher {

    private static final String ID = "argon2id";

    private final Argon2PasswordEncoder argon2;
    private final DelegatingPasswordEncoder encoder;
    private final Semaphore slots;
    private final long waitMillis;
    private final String decoy;

    public PasswordHasher(IdentityProperties properties) {
        // 16-byte salt, 32-byte hash, 1 lane; memory and iterations never below OWASP's minimum.
        this.argon2 = new Argon2PasswordEncoder(16, 32, 1, properties.argon2MemoryKib(), properties.argon2Iterations());
        this.encoder = new DelegatingPasswordEncoder(ID, Map.of(ID, argon2));
        this.slots = new Semaphore(properties.hashConcurrency(), true);
        this.waitMillis = properties.hashWaitMillis();
        this.decoy = encoder.encode("decoy-" + Keys.oneTimeCredential());
    }

    /** Hashes a password that has already passed the policy (NFC form). */
    public String hash(String normalizedPassword) {
        return limited(() -> encoder.encode(normalizedPassword));
    }

    /**
     * Checks a password. With no stored hash it checks against a decoy and answers false, so
     * the work done does not depend on whether the account exists.
     */
    public boolean matches(String normalizedPassword, String storedHash) {
        return limited(() -> {
            boolean matched = encoder.matches(normalizedPassword, storedHash == null ? decoy : storedHash);
            return matched && storedHash != null;
        });
    }

    /** True when the stored hash was made with weaker parameters than the current ones. */
    public boolean needsUpgrade(String storedHash) {
        return encoder.upgradeEncoding(storedHash);
    }

    private <T> T limited(Supplier<T> work) {
        boolean acquired;
        try {
            acquired = slots.tryAcquire(waitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            throw ApiException.of(HttpStatus.SERVICE_UNAVAILABLE, "unavailable", "Too many password checks at once").header("Retry-After", "5");
        }
        try {
            return work.get();
        } finally {
            slots.release();
        }
    }
}
