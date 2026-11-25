package org.apvgakasinay.identity.internal;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Identity settings, read once at start-up, with the rules that keep development conveniences
 * out of production: outside the dev and test profiles the session cookie must be the secure
 * host-only one, public sign-up must be closed, and the secret keys must be supplied.
 */
@Component
public class IdentityProperties {

    /** OWASP's minimum for Argon2id. Configuration may raise these, never lower them. */
    static final int MIN_ARGON2_MEMORY_KIB = 19456;
    static final int MIN_ARGON2_ITERATIONS = 2;

    private final boolean development;
    private final boolean registrationOpen;
    private final String cookieName;
    private final boolean cookieSecure;
    private final byte[] encryptionKey;
    private final byte[] hashKey;
    private final List<String> trustedProxies;
    private final List<String> allowedOrigins;
    private final int argon2MemoryKib;
    private final int argon2Iterations;
    private final int hashConcurrency;
    private final long hashWaitMillis;
    private final String blocklistPath;

    public IdentityProperties(Environment environment) {
        Set<String> profiles = Set.of(environment.getActiveProfiles());
        this.development = profiles.contains("dev") || profiles.contains("test");
        this.registrationOpen = environment.getProperty("apv.identity.registration-open", Boolean.class, false);
        this.cookieName = environment.getProperty("apv.identity.session-cookie.name", "__Host-apv_session");
        this.cookieSecure = environment.getProperty("apv.identity.session-cookie.secure", Boolean.class, true);
        this.trustedProxies = list(environment.getProperty("apv.identity.trusted-proxies", ""));
        this.allowedOrigins = list(environment.getProperty("apv.identity.allowed-origins", ""));
        this.argon2MemoryKib = environment.getProperty("apv.identity.argon2.memory-kib", Integer.class, MIN_ARGON2_MEMORY_KIB);
        this.argon2Iterations = environment.getProperty("apv.identity.argon2.iterations", Integer.class, MIN_ARGON2_ITERATIONS);
        this.hashConcurrency = environment.getProperty("apv.identity.hash-concurrency", Integer.class, 4);
        this.hashWaitMillis = environment.getProperty("apv.identity.hash-wait-millis", Long.class, 2000L);
        this.blocklistPath = environment.getProperty("apv.identity.password-blocklist-path", "");

        String encryption = environment.getProperty("apv.identity.mfa-encryption-key", "");
        String hashing = environment.getProperty("apv.identity.hash-key", "");
        if (encryption.isBlank() || hashing.isBlank()) {
            if (!development) {
                throw new IllegalStateException(
                        "APV_MFA_ENCRYPTION_KEY and APV_HASH_KEY must be set (32 random bytes each, Base64). They are generated only in the dev and test profiles.");
            }
            // Development only: keys that live as long as this process. Enrolled second factors do not survive a restart.
            this.encryptionKey = Keys.random(32);
            this.hashKey = Keys.random(32);
        } else {
            this.encryptionKey = decodeKey("APV_MFA_ENCRYPTION_KEY", encryption);
            this.hashKey = decodeKey("APV_HASH_KEY", hashing);
        }

        if (argon2MemoryKib < MIN_ARGON2_MEMORY_KIB || argon2Iterations < MIN_ARGON2_ITERATIONS) {
            throw new IllegalStateException("Argon2id parameters may be raised, never set below 19456 KiB and 2 iterations.");
        }
        if (hashConcurrency < 1) {
            throw new IllegalStateException("apv.identity.hash-concurrency must be at least 1.");
        }
        if (!development) {
            if (!cookieSecure || !cookieName.startsWith("__Host-")) {
                throw new IllegalStateException(
                        "Outside the dev and test profiles the session cookie must be Secure and use the __Host- prefix.");
            }
            if (registrationOpen) {
                throw new IllegalStateException(
                        "Public sign-up cannot be opened outside the dev and test profiles: the site cannot yet verify an email address.");
            }
        }
    }

    private static List<String> list(String value) {
        return Arrays.stream(value.split(",")).map(String::trim).filter(item -> !item.isEmpty()).toList();
    }

    private static byte[] decodeKey(String name, String value) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(value.trim());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException(name + " is not valid Base64.");
        }
        if (key.length != 32) {
            throw new IllegalStateException(name + " must be exactly 32 bytes, Base64-encoded.");
        }
        return key;
    }

    public boolean development() {
        return development;
    }

    public boolean registrationOpen() {
        return registrationOpen;
    }

    public String cookieName() {
        return cookieName;
    }

    public boolean cookieSecure() {
        return cookieSecure;
    }

    byte[] encryptionKey() {
        return encryptionKey;
    }

    byte[] hashKey() {
        return hashKey;
    }

    public List<String> trustedProxies() {
        return trustedProxies;
    }

    public List<String> allowedOrigins() {
        return allowedOrigins;
    }

    int argon2MemoryKib() {
        return argon2MemoryKib;
    }

    int argon2Iterations() {
        return argon2Iterations;
    }

    int hashConcurrency() {
        return hashConcurrency;
    }

    long hashWaitMillis() {
        return hashWaitMillis;
    }

    String blocklistPath() {
        return blocklistPath;
    }
}
