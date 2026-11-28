package org.apvgakasinay.donations.internal;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.apvgakasinay.shared.AddressLimiter;
import org.apvgakasinay.shared.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The rate-limit counters of contract/donation-lifecycle.md 12.2, with the mechanism of
 * identity-and-security.md 7.3: an attempt is counted by one statement that inserts the row or
 * adds one and returns the new count, in its own transaction, before the work is done, so
 * parallel requests cannot all slip under a limit together. Keys are stored as keyed hashes:
 * the table holds no address, no reference and no account.
 *
 * <p>Nothing here escalates and nothing an outsider does disables reporting for good: every
 * refusal ends by itself, after at most 60 minutes.
 */
@Component
public class DonationThrottle implements AddressLimiter {

    /** @param cooldown how long to refuse once the limit is passed; null means until the window ends */
    public record Limit(String scope, Duration window, int max, Duration cooldown) {
    }

    static final Limit METHODS_BY_ADDRESS = new Limit("methods.address", Duration.ofMinutes(1), 120, null);
    static final Limit REPORTS_BY_ADDRESS = new Limit("report.address", Duration.ofHours(1), 6, null);
    static final Limit EVIDENCE_BY_ADDRESS = new Limit("evidence.address", Duration.ofHours(1), 12, null);
    static final Limit STATUS_BY_ADDRESS = new Limit("status.address", Duration.ofMinutes(15), 10, null);
    static final Limit STATUS_WRONG_BY_REFERENCE = new Limit("status.reference", Duration.ofHours(1), 10, Duration.ofMinutes(15));
    static final Limit STAFF_READS_BY_ACCOUNT = new Limit("staff.read.account", Duration.ofMinutes(1), 300, null);
    static final Limit STAFF_WRITES_BY_ACCOUNT = new Limit("staff.write.account", Duration.ofMinutes(1), 60, null);

    /** The names {@code ApiOperation.limit} uses for the per-address limits, counted at step 5. */
    private static final Map<String, Limit> BY_ADDRESS = Map.of(
            "giving.methods", METHODS_BY_ADDRESS,
            "giving.report", REPORTS_BY_ADDRESS,
            "giving.evidence", EVIDENCE_BY_ADDRESS,
            "giving.status", STATUS_BY_ADDRESS);

    /** Rows older than this can never matter again: no window and no cooldown lasts longer than an hour. */
    static final Duration RETENTION = Duration.ofHours(2);

    private final JdbcClient jdbc;
    private final DonationKeys keys;
    private final DonationClock clock;

    public DonationThrottle(JdbcClient jdbc, DonationKeys keys, DonationClock clock) {
        this.jdbc = jdbc;
        this.keys = keys;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OptionalLong count(String limit, String clientAddress) {
        Limit known = BY_ADDRESS.get(limit);
        return known == null ? OptionalLong.empty() : OptionalLong.of(count(known, clientAddress));
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
        byte[] hash = keys.throttleHash(limit.scope() + "|" + key);

        // One key at a time, on every instance: reading the cooldown marker, advancing the counter
        // and starting a cooldown are several statements. Released when this small transaction ends.
        jdbc.sql("select pg_advisory_xact_lock(:lock)").param("lock", ByteBuffer.wrap(hash).getLong()).query((rs, row) -> 0).list();

        if (limit.cooldown() != null) {
            List<Instant> blocks = jdbc.sql("""
                            select window_start from donation_throttle where scope = :scope and key_hash = :key
                             order by window_start desc limit 1
                            """)
                    .param("scope", limit.scope() + "#blocked").param("key", hash)
                    .query((rs, row) -> rs.getTimestamp(1).toInstant()).list();
            if (!blocks.isEmpty() && now.isBefore(blocks.get(0).plus(limit.cooldown()))) {
                return Math.max(1, Duration.between(now, blocks.get(0).plus(limit.cooldown())).toSeconds());
            }
        }

        Instant windowStart = windowStart(now, limit.window());
        int attempts = increment(limit.scope(), hash, windowStart);
        if (attempts <= limit.max()) {
            return 0;
        }
        if (limit.cooldown() == null) {
            return Math.max(1, Duration.between(now, windowStart.plus(limit.window())).toSeconds());
        }
        // Start a bounded cooldown and reset the counter, so the refusal ends after the cooldown.
        jdbc.sql("""
                        insert into donation_throttle (scope, key_hash, window_start, attempts) values (:scope, :key, :now, 1)
                        on conflict (scope, key_hash, window_start) do nothing
                        """)
                .param("scope", limit.scope() + "#blocked").param("key", hash).param("now", Timestamp.from(now)).update();
        jdbc.sql("delete from donation_throttle where scope = :scope and key_hash = :key")
                .param("scope", limit.scope()).param("key", hash).update();
        return limit.cooldown().toSeconds();
    }

    /** Takes one attempt back: used when a counted attempt turned out to be a success. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void forgive(Limit limit, String key) {
        jdbc.sql("update donation_throttle set attempts = attempts - 1 where scope = :scope and key_hash = :key and attempts > 0")
                .param("scope", limit.scope()).param("key", keys.throttleHash(limit.scope() + "|" + key)).update();
    }

    /**
     * Adds one to a counter of the whole site and returns its new value and the seconds until its
     * window ends. Used by the brakes of 12.3, which decide what the count means.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Tally tally(String scope, Duration window) {
        Instant now = clock.now();
        Instant windowStart = windowStart(now, window);
        int attempts = increment(scope, keys.throttleHash(scope + "|site"), windowStart);
        return new Tally(attempts, Math.max(1, Duration.between(now, windowStart.plus(window)).toSeconds()));
    }

    /** A site-wide count in its current window, and how long that window still lasts. */
    public record Tally(int count, long secondsLeft) {
    }

    /** Removes rows that can no longer affect any decision, a bounded number per call. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int removeExpired(int atMost) {
        return jdbc.sql("""
                        delete from donation_throttle
                         where ctid in (select ctid from donation_throttle where window_start < :before limit :atMost)
                        """)
                .param("before", Timestamp.from(clock.now().minus(RETENTION))).param("atMost", atMost).update();
    }

    private int increment(String scope, byte[] hash, Instant windowStart) {
        return jdbc.sql("""
                        insert into donation_throttle (scope, key_hash, window_start, attempts)
                        values (:scope, :key, :window, 1)
                        on conflict (scope, key_hash, window_start) do update set attempts = donation_throttle.attempts + 1
                        returning attempts
                        """)
                .param("scope", scope).param("key", hash).param("window", Timestamp.from(windowStart))
                .query(Integer.class).single();
    }

    private static Instant windowStart(Instant now, Duration window) {
        long seconds = window.toSeconds();
        return Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond(), seconds) * seconds);
    }

    public static ApiException tooMany(long retryAfterSeconds) {
        return ApiException.of(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "Too many requests")
                .header("Retry-After", String.valueOf(Math.min(3600, Math.max(1, retryAfterSeconds))));
    }
}
