package org.apvgakasinay.identity.internal;

import java.time.Duration;
import java.time.Instant;
import org.apvgakasinay.shared.ApiException;
import org.apvgakasinay.shared.AppClock;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rate-limit counters in PostgreSQL (identity-and-security.md, section 7).
 *
 * An attempt is counted by one statement that inserts the row or adds one and returns the new
 * count, in its own transaction. Callers count the attempt first and look at a secret only if
 * the count is within the limit, so parallel requests cannot all slip under it together.
 * Nothing here ever disables an account: every refusal ends by itself.
 */
@Component
public class Throttle {

    /**
     * @param cooldown how long to refuse once the limit is passed; null means until the window ends
     */
    public record Limit(String scope, Duration window, int max, Duration cooldown) {
    }

    public static final Limit PASSWORD_BY_EMAIL_AND_ADDRESS = new Limit("password.pair", Duration.ofMinutes(15), 5, null);
    public static final Limit PASSWORD_BY_ACCOUNT = new Limit("password.account", Duration.ofHours(1), 20, Duration.ofMinutes(15));
    public static final Limit SIGN_IN_BY_ADDRESS = new Limit("signin.address", Duration.ofMinutes(15), 30, null);
    public static final Limit SECOND_FACTOR_BY_ACCOUNT = new Limit("mfa.account", Duration.ofHours(1), 10, Duration.ofMinutes(15));
    public static final Limit CREDENTIAL_BY_ADDRESS = new Limit("credential.address", Duration.ofHours(1), 10, Duration.ofMinutes(15));
    public static final Limit SIGN_UP_BY_ADDRESS = new Limit("signup.address", Duration.ofHours(1), 5, null);
    public static final Limit TOKEN_BY_ADDRESS = new Limit("token.address", Duration.ofMinutes(1), 120, null);
    /**
     * Requests, per client address, to the operations of a signed-in session that check a
     * password (password change, recovery-code regeneration). Section 7.1 of the contract says
     * these draw on the client address; section 7.2 gives that counter no number. It is kept
     * apart from the sign-in counter and given the same number. Reported as a contract question.
     */
    public static final Limit PROTECTED_BY_ADDRESS = new Limit("protected.address", Duration.ofMinutes(15), 30, null);

    /** Every limit there is. Nothing a counter needs is older than {@link #RETENTION}. */
    public static final java.util.List<Limit> ALL = java.util.List.of(PASSWORD_BY_EMAIL_AND_ADDRESS, PASSWORD_BY_ACCOUNT, SIGN_IN_BY_ADDRESS,
            SECOND_FACTOR_BY_ACCOUNT, CREDENTIAL_BY_ADDRESS, SIGN_UP_BY_ADDRESS, TOKEN_BY_ADDRESS, PROTECTED_BY_ADDRESS);

    /** Rows older than this can never matter again: the longest window and the longest cooldown are both far shorter. */
    public static final Duration RETENTION = Duration.ofHours(2);

    private final JdbcClient jdbc;
    private final Keys keys;
    private final AppClock clock;

    public Throttle(JdbcClient jdbc, Keys keys, AppClock clock) {
        this.jdbc = jdbc;
        this.keys = keys;
        this.clock = clock;
    }

    /** Counts one attempt and refuses with 429 and Retry-After when the limit is passed. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void attempt(Limit limit, String key) {
        long wait = count(limit, key);
        if (wait > 0) {
            throw tooMany(wait);
        }
    }

    /** Counts one attempt. Returns 0 when it is within the limit, otherwise the seconds to wait. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long count(Limit limit, String key) {
        Instant now = clock.now();
        byte[] hash = keys.throttleKeyHash(limit.scope() + "|" + key);

        // One key at a time, on every instance. Reading the cooldown marker, advancing the counter
        // and starting a cooldown are several statements; without this, requests arriving together
        // at the boundary could each see "no cooldown yet" and act on a counter another one had
        // just reset. The lock is released when this small transaction ends.
        jdbc.sql("select pg_advisory_xact_lock(:lock)").param("lock", java.nio.ByteBuffer.wrap(hash).getLong())
                .query((rs, row) -> 0).list();

        if (limit.cooldown() != null) {
            java.util.List<Instant> blocks = jdbc.sql("""
                            select window_start from auth_throttle where scope = :scope and key_hash = :key
                             order by window_start desc limit 1
                            """)
                    .param("scope", limit.scope() + "#blocked").param("key", hash)
                    .query((rs, row) -> rs.getTimestamp(1).toInstant()).list();
            if (!blocks.isEmpty() && now.isBefore(blocks.get(0).plus(limit.cooldown()))) {
                return Math.max(1, Duration.between(now, blocks.get(0).plus(limit.cooldown())).toSeconds());
            }
        }

        long windowSeconds = limit.window().toSeconds();
        Instant windowStart = Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond(), windowSeconds) * windowSeconds);
        int attempts = jdbc.sql("""
                        insert into auth_throttle (scope, key_hash, window_start, attempts)
                        values (:scope, :key, :window, 1)
                        on conflict (scope, key_hash, window_start) do update set attempts = auth_throttle.attempts + 1
                        returning attempts
                        """)
                .param("scope", limit.scope()).param("key", hash).param("window", java.sql.Timestamp.from(windowStart))
                .query(Integer.class).single();
        if (attempts <= limit.max()) {
            return 0;
        }
        if (limit.cooldown() == null) {
            return Math.max(1, Duration.between(now, windowStart.plus(limit.window())).toSeconds());
        }
        // Start a bounded cooldown and reset the counter, so the refusal ends after the cooldown.
        jdbc.sql("""
                        insert into auth_throttle (scope, key_hash, window_start, attempts) values (:scope, :key, :now, 1)
                        on conflict (scope, key_hash, window_start) do nothing
                        """)
                .param("scope", limit.scope() + "#blocked").param("key", hash).param("now", java.sql.Timestamp.from(now)).update();
        jdbc.sql("delete from auth_throttle where scope = :scope and key_hash = :key")
                .param("scope", limit.scope()).param("key", hash).update();
        return limit.cooldown().toSeconds();
    }

    /** Forgets the counted attempts of a key, after a success. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clear(Limit limit, String key) {
        jdbc.sql("delete from auth_throttle where scope = :scope and key_hash = :key")
                .param("scope", limit.scope()).param("key", keys.throttleKeyHash(limit.scope() + "|" + key)).update();
    }

    /** Takes one attempt back: used when a counted attempt turned out to be a success. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void forgive(Limit limit, String key) {
        jdbc.sql("update auth_throttle set attempts = attempts - 1 where scope = :scope and key_hash = :key and attempts > 0")
                .param("scope", limit.scope()).param("key", keys.throttleKeyHash(limit.scope() + "|" + key)).update();
    }

    /**
     * Removes rows that can no longer affect any decision, a bounded number per call. A window
     * or a cooldown marker is kept for at least {@link #RETENTION} after it began, which is
     * longer than any window or cooldown lasts, so an active cooldown is never removed.
     *
     * @return how many rows were removed
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int removeExpired(int atMost) {
        return jdbc.sql("""
                        delete from auth_throttle
                         where ctid in (select ctid from auth_throttle where window_start < :before limit :atMost)
                        """)
                .param("before", java.sql.Timestamp.from(clock.now().minus(RETENTION))).param("atMost", atMost).update();
    }

    public static ApiException tooMany(long retryAfterSeconds) {
        return ApiException.of(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "Too many requests")
                .header("Retry-After", String.valueOf(Math.max(1, retryAfterSeconds)));
    }
}
