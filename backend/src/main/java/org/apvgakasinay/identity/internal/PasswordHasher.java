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
    private final java.util.concurrent.ExecutorService workers;
    private final long waitMillis;
    private final String decoy;
    private final java.util.concurrent.atomic.AtomicInteger running = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger peak = new java.util.concurrent.atomic.AtomicInteger();

    public PasswordHasher(IdentityProperties properties) {
        // 16-byte salt, 32-byte hash, 1 lane; memory and iterations never below OWASP's minimum.
        this.argon2 = new Argon2PasswordEncoder(16, 32, 1, properties.argon2MemoryKib(), properties.argon2Iterations());
        this.encoder = new DelegatingPasswordEncoder(ID, Map.of(ID, argon2));
        this.slots = new Semaphore(properties.hashConcurrency(), true);
        java.util.concurrent.atomic.AtomicInteger number = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.ThreadPoolExecutor pool = new java.util.concurrent.ThreadPoolExecutor(
                properties.hashConcurrency(), properties.hashConcurrency(), 30, TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>(),
                task -> {
                    Thread thread = new Thread(task, "password-hash-" + number.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        pool.allowCoreThreadTimeOut(true);
        this.workers = pool;
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

    /** The largest number of hashes that have ever run at the same moment in this process. */
    public int peakConcurrency() {
        return peak.get();
    }

    /** True when the stored hash was made with weaker parameters than the current ones. */
    public boolean needsUpgrade(String storedHash) {
        return encoder.upgradeEncoding(storedHash);
    }

    /**
     * Runs one hash in one of the few slots.
     *
     * The work is done on a small pool of ordinary threads, never on the request's own thread.
     * Requests run on virtual threads, which share a handful of carrier threads and are not
     * interrupted while they compute: a hash computed there would hold a carrier for its whole
     * duration, and with every carrier hashing, nothing else in the server would run, not even
     * the timeout of a request waiting for a slot. Measured: forty simultaneous sign-ins were
     * all served, the last after twenty seconds, and none was turned away at two.
     *
     * The wait is also checked against the clock after a slot is obtained, so a request that
     * waited longer than allowed is turned away even if it was woken late.
     */
    private <T> T limited(Supplier<T> work) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
        boolean acquired;
        try {
            acquired = slots.tryAcquire(waitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (acquired && System.nanoTime() - deadline > 0) {
            slots.release();
            acquired = false;
        }
        if (!acquired) {
            throw ApiException.of(HttpStatus.SERVICE_UNAVAILABLE, "unavailable", "Too many password checks at once").header("Retry-After", "5");
        }
        try {
            return workers.submit(() -> {
                int now = running.incrementAndGet();
                peak.accumulateAndGet(now, Math::max);
                try {
                    return work.get();
                } finally {
                    running.decrementAndGet();
                }
            }).get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while hashing");
        } catch (java.util.concurrent.ExecutionException failed) {
            throw failed.getCause() instanceof RuntimeException runtime ? runtime : new IllegalStateException(failed.getCause());
        } finally {
            slots.release();
        }
    }

    @jakarta.annotation.PreDestroy
    void stop() {
        workers.shutdownNow();
    }
}
