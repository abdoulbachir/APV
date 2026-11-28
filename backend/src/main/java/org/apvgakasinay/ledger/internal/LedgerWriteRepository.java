package org.apvgakasinay.ledger.internal;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
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

    /** A new opaque public identifier: a short prefix and 22 URL-safe random characters (about 131 bits). */
    public static String publicId(String prefix) {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).substring(0, 22);
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
