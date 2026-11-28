package org.apvgakasinay.donations.internal;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import org.apvgakasinay.shared.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The idempotency store (contract/donation-lifecycle.md, 3.6 and 6.6).
 *
 * <p>A key is claimed by the first statement of the operation's transaction: an insert that
 * does nothing when the key is already there. Of two simultaneous requests with one key, the
 * second waits at that insert for the first to commit or roll back, then finds its row or takes
 * the key itself. An operation that is refused rolls back, so its key was not consumed.
 *
 * <p>What is stored: a keyed hash of the key, a keyed hash of the request, the status answered
 * and the identifier of the gift the answer was about. Neither the key nor any part of an answer
 * is stored: a repeated request is answered from the gift, and the secrets of a receipt are
 * computed again, never read back.
 */
@Component
class Idempotency {

    /** Guest reports: one namespace for everyone, remembered for 24 hours (3.6). */
    static final String REPORT_SCOPE = "guest";
    static final String REPORT_OPERATION = "report";
    static final Duration REPORT_WINDOW = Duration.ofHours(24);
    /** Staff operations: a key belongs to the account that used it, remembered for 7 days (6.6). */
    static final String STAFF_OPERATION = "donation";
    static final Duration STAFF_WINDOW = Duration.ofDays(7);

    /** A request this key was already used for, and what it was answered. */
    record Earlier(int status, long donationId) {
    }

    private final JdbcClient jdbc;
    private final DonationKeys keys;
    private final DonationClock clock;

    Idempotency(JdbcClient jdbc, DonationKeys keys, DonationClock clock) {
        this.jdbc = jdbc;
        this.keys = keys;
        this.clock = clock;
    }

    static String staffScope(long accountId) {
        return "account:" + accountId;
    }

    /** The hash a request is recognised by: what it does, what it is about, and its body, under a key. */
    byte[] requestHash(String action, String target, byte[] body) {
        byte[] head = (action + "\n" + target + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] all = Arrays.copyOf(head, head.length + body.length);
        System.arraycopy(body, 0, all, head.length, body.length);
        return keys.idempotencyHash(all);
    }

    /**
     * Claims the key for this request, in the caller's transaction.
     *
     * @return empty when the key is now this request's, which must end with {@link #complete};
     *         the earlier answer when the same request was already made with it
     * @throws ApiException 409 idempotency_key_reused when the key was used for a different request
     */
    Optional<Earlier> claim(String scope, String operation, String key, byte[] requestHash, Duration window) {
        Instant now = clock.now();
        byte[] keyHash = keys.idempotencyHash(key.getBytes(StandardCharsets.UTF_8));
        // A key last seen longer ago than it is remembered for is a key not seen.
        jdbc.sql("delete from idempotency_record where scope = :scope and operation = :operation and key_hash = :key and created_at < :before")
                .param("scope", scope).param("operation", operation).param("key", keyHash)
                .param("before", Timestamp.from(now.minus(window))).update();
        int claimed = jdbc.sql("""
                        insert into idempotency_record (scope, operation, key_hash, request_hash, status_code, response_json, created_at)
                        values (:scope, :operation, :key, :request, 0, '', :now)
                        on conflict (scope, operation, key_hash) do nothing
                        """)
                .param("scope", scope).param("operation", operation).param("key", keyHash).param("request", requestHash)
                .param("now", Timestamp.from(now)).update();
        if (claimed == 1) {
            return Optional.empty();
        }
        return Optional.of(jdbc.sql("""
                        select request_hash, status_code, response_json from idempotency_record
                         where scope = :scope and operation = :operation and key_hash = :key
                        """)
                .param("scope", scope).param("operation", operation).param("key", keyHash)
                .query((rs, row) -> {
                    if (!java.security.MessageDigest.isEqual(rs.getBytes("request_hash"), requestHash)) {
                        throw ApiException.of(HttpStatus.CONFLICT, "idempotency_key_reused", "This key was already used for a different request");
                    }
                    return new Earlier(rs.getInt("status_code"), Long.parseLong(rs.getString("response_json")));
                })
                .single());
    }

    /** Records what the request that claimed the key was answered. */
    void complete(String scope, String operation, String key, int status, long donationId) {
        jdbc.sql("""
                        update idempotency_record set status_code = :status, response_json = :donation
                         where scope = :scope and operation = :operation and key_hash = :key
                        """)
                .param("status", status).param("donation", String.valueOf(donationId))
                .param("scope", scope).param("operation", operation)
                .param("key", keys.idempotencyHash(key.getBytes(StandardCharsets.UTF_8))).update();
    }

    /** Removes keys older than they are remembered for, a bounded number per call. */
    int removeExpired(int atMost) {
        Instant now = clock.now();
        return jdbc.sql("""
                        delete from idempotency_record
                         where ctid in (select ctid from idempotency_record
                                         where (operation = :report and created_at < :reportBefore)
                                            or (operation <> :report and created_at < :staffBefore)
                                         limit :atMost)
                        """)
                .param("report", REPORT_OPERATION)
                .param("reportBefore", Timestamp.from(now.minus(REPORT_WINDOW)))
                .param("staffBefore", Timestamp.from(now.minus(STAFF_WINDOW)))
                .param("atMost", atMost).update();
    }
}
