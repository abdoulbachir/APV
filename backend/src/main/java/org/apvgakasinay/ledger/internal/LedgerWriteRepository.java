package org.apvgakasinay.ledger.internal;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The write side of the ledger: the only code that inserts {@code ledger_entry} rows and the
 * {@code donation_listing} that accompanies a confirmed gift (ADR 0001). Every statement runs
 * in the caller's transaction; the database triggers of migration V1/V2 remain the final guard
 * on the append-only rule, the correction limit, the discretionary bound and the platform bound.
 */
@Repository
public class LedgerWriteRepository {

    /** The platform-wide movement bound of {@code ledger_platform_total_within_bound} (10^15 cents). */
    public static final long PLATFORM_BOUND_CENTS = 1_000_000_000_000_000L;

    /** The name of the check constraint that enforces the platform bound, matched to map its violation. */
    public static final String PLATFORM_BOUND_CONSTRAINT = "ledger_platform_total_within_bound";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;

    public LedgerWriteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Digits and upper-case letters in their byte order, so identifiers sort the way they were made. */
    private static final char[] SYMBOLS = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static long lastMillis;

    /**
     * A new public identifier: a short prefix, ten symbols for the millisecond it was made in and
     * twelve random symbols (sixty bits).
     *
     * <p>Entries are dated to the second, and the public record breaks ties by identifier. An
     * identifier that follows the clock keeps entries of one second in the order they were
     * written, so a correction is never listed before the entry it corrects and a page already
     * read never gains an entry in its past (contract/donation-lifecycle.md, 9.1). It says
     * nothing the entry's public date does not, and nothing about the gift behind it.
     */
    public static String publicId(String prefix) {
        long millis = nextMillis();
        StringBuilder id = new StringBuilder(prefix);
        for (int shift = 45; shift >= 0; shift -= 5) {
            id.append(SYMBOLS[(int) ((millis >> shift) & 0x1f)]);
        }
        for (int i = 0; i < 12; i++) {
            id.append(SYMBOLS[RANDOM.nextInt(SYMBOLS.length)]);
        }
        return id.toString();
    }

    /** The current millisecond, never the same twice and never going back within this process. */
    private static synchronized long nextMillis() {
        long now = System.currentTimeMillis();
        lastMillis = now > lastMillis ? now : lastMillis + 1;
        return lastMillis;
    }

    /** The current platform-wide gross total, read without a lock for a pre-insert check. */
    public long platformGross() {
        return jdbc.sql("select gross_cents from ledger_platform_total").query(Long.class).single();
    }

    public long insertDonation(long caseId, long amountCents, Instant effectiveAt, String publicId) {
        return jdbc.sql("""
                        insert into ledger_entry (public_id, case_id, kind, amount_cents, effective_at)
                        values (:publicId, :caseId, 'donation', :amount, :effectiveAt)
                        returning id
                        """)
                .param("publicId", publicId)
                .param("caseId", caseId)
                .param("amount", amountCents)
                .param("effectiveAt", OffsetDateTime.ofInstant(effectiveAt, ZoneOffset.UTC))
                .query(Long.class)
                .single();
    }

    public void insertListing(long entryId, String publicId, String visibility, String donorName) {
        jdbc.sql("""
                 insert into donation_listing (ledger_entry_id, public_id, visibility, donor_name, avatar_media_id, avatar_consent)
                 values (:entry, :publicId, :visibility, :name, null, false)
                 """)
                .param("entry", entryId)
                .param("publicId", publicId)
                .param("visibility", visibility)
                .param("name", donorName)
                .update();
    }

    /**
     * Locks a {@code donation} entry for the duration of the transaction and returns the case it
     * is on and its amount. The lock is what the correction limit of 8.2 is checked under.
     */
    public LockedDonation lockDonation(long entryId) {
        return jdbc.sql("select case_id, amount_cents from ledger_entry where id = :id and kind = 'donation' for update")
                .param("id", entryId)
                .query((rs, row) -> new LockedDonation(rs.getLong("case_id"), rs.getLong("amount_cents")))
                .optional()
                .orElseThrow(() -> new IllegalStateException("no donation ledger entry " + entryId));
    }

    /** The cents already corrected against a donation entry (reversals and refunds together). */
    public long correctionsTotal(long donationEntryId) {
        return jdbc.sql("select coalesce(sum(amount_cents), 0) from ledger_entry where corrects_id = :id")
                .param("id", donationEntryId)
                .query(Long.class)
                .single();
    }

    public long insertCorrection(long caseId, long donationEntryId, String kind, long amountCents, Instant effectiveAt, String publicId) {
        return jdbc.sql("""
                        insert into ledger_entry (public_id, case_id, kind, amount_cents, effective_at, corrects_id)
                        values (:publicId, :caseId, :kind, :amount, :effectiveAt, :corrects)
                        returning id
                        """)
                .param("publicId", publicId)
                .param("caseId", caseId)
                .param("kind", kind)
                .param("amount", amountCents)
                .param("effectiveAt", OffsetDateTime.ofInstant(effectiveAt, ZoneOffset.UTC))
                .param("corrects", donationEntryId)
                .query(Long.class)
                .single();
    }

    /**
     * Lowers a listing's visibility to {@code anonymous} or {@code hidden} and drops the name: the
     * caller only ever restricts (8.4), so the name is never shown again from the next request.
     */
    public void restrictListing(long donationEntryId, String visibility) {
        jdbc.sql("update donation_listing set visibility = :visibility, donor_name = null where ledger_entry_id = :entry")
                .param("visibility", visibility)
                .param("entry", donationEntryId)
                .update();
    }

    /** The case and amount of a locked donation entry. */
    public record LockedDonation(long caseId, long amountCents) {
    }
}
