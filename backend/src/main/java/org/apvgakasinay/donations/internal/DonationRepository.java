package org.apvgakasinay.donations.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * All SQL of gifts. The donations module owns these tables; it reads money through the ledger
 * module's views ({@code ledger_entry_detail}, {@code case_balance}) and never reads the cases
 * or ledger tables themselves.
 *
 * <p>Private details (payer name, payment reference, contact, notes) are read only by the
 * review queries. The queries behind a donor's receipt and status name their columns one by
 * one and select none of them (contract/donation-lifecycle.md, 15).
 *
 * <p>A state change is a conditional update: it succeeds for exactly one of two simultaneous
 * requests, whatever lock either holds.
 */
@Repository
class DonationRepository {

    /** One gift with everything recorded about it. Read by review queries only. */
    record Gift(long id, String publicId, String reference, String origin, String state, Long accountId,
                String reportedCaseCode, Long reportedAmount, LocalDate paidOn, Instant reportedAt,
                String payerName, String paymentReference, String contact,
                String methodId, Long detailsId, String methodDescription,
                String displayMode, String publicName, String listing,
                Instant decidedAt, Long decidedBy, Long confirmedCaseId, String confirmedCaseCode, Long confirmedAmount,
                LocalDate receivedOn, String providerReference, String rejectionReason, String note,
                Long ledgerEntryId, Long reentryOf, Instant erasedAt, Instant createdAt) {

        boolean confirmed() {
            return state.equals("confirmed");
        }
    }

    /** What the donor's receipt and status are built from. No private detail is selected. */
    record Tracked(long id, String publicId, String reference, byte[] secret, String origin, String state, String reportedCaseCode, Long reportedAmount,
                   Instant reportedAt, Instant decidedAt, Long confirmedAmount, String confirmedCaseCode, String rejectionReason,
                   Long netAmount, boolean evidenceAttached, boolean evidenceEverAttached) {
    }

    /** One row of the review list. */
    record Listed(Gift gift, Long netAmount, String evidence, boolean supersededDetails, boolean possibleDuplicate, boolean shortfall,
                  boolean reentered) {
    }

    record CorrectionRow(String ledgerPublicId, String kind, long amount, Instant at, String reason, String providerReference) {
    }

    record HistoryRow(Instant at, String action, Long actorAccountId) {
    }

    record Hint(String publicId, String reference, String state, long amount, Instant reportedAt) {
    }

    record EvidenceRow(long donationId, String storageKey, String format, long bytes, Instant deletedAt) {
    }

    private static final String GIFT = """
            select d.id, d.public_id, d.reference, d.origin, d.state, d.account_id,
                   d.reported_case_code, d.reported_amount_cents, d.paid_on, d.reported_at,
                   d.payer_name, d.payment_reference, d.contact,
                   d.payment_method_id, d.payment_details_id, d.method_description,
                   d.display_mode, d.public_name, d.listing,
                   d.decided_at, d.decided_by_account_id, d.confirmed_case_id, d.confirmed_case_code, d.confirmed_amount_cents,
                   d.received_on, d.provider_reference, d.rejection_reason, d.note,
                   d.ledger_entry_id, d.reentry_of, d.private_details_erased_at, d.created_at
              from donation d
            """;

    private static final String TRACKED = """
            select d.id, d.public_id, d.reference, d.secret, d.origin, d.state, d.reported_case_code, d.reported_amount_cents, d.reported_at,
                   d.decided_at, d.confirmed_amount_cents, d.confirmed_case_code, d.rejection_reason,
                   (select e.amount_cents - e.corrected_cents from ledger_entry_detail e where e.id = d.ledger_entry_id) as net_cents,
                   exists (select 1 from donation_evidence v where v.donation_id = d.id and v.deleted_at is null) as evidence_attached,
                   exists (select 1 from donation_evidence v where v.donation_id = d.id) as evidence_ever
              from donation d
            """;

    /** Two gifts look like one payment: same method, same amount, same payer name ignoring case and white space, within 7 days. */
    private static final String SAME_PAYMENT = """
                  o.id <> d.id
              and o.payment_method_id = d.payment_method_id
              and coalesce(o.reported_amount_cents, o.confirmed_amount_cents) = coalesce(d.reported_amount_cents, d.confirmed_amount_cents)
              and o.payer_name is not null and d.payer_name is not null
              and lower(regexp_replace(o.payer_name, '\\s+', '', 'g')) = lower(regexp_replace(d.payer_name, '\\s+', '', 'g'))
              and abs(extract(epoch from (coalesce(o.reported_at, o.created_at) - coalesce(d.reported_at, d.created_at)))) <= 604800
              and o.reentry_of is distinct from d.id and d.reentry_of is distinct from o.id
            """;

    private final JdbcClient jdbc;

    DonationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Gift gift(ResultSet rs, int row) throws SQLException {
        return new Gift(rs.getLong("id"), rs.getString("public_id"), rs.getString("reference"), rs.getString("origin"), rs.getString("state"),
                rs.getObject("account_id", Long.class),
                rs.getString("reported_case_code"), rs.getObject("reported_amount_cents", Long.class),
                rs.getObject("paid_on", LocalDate.class), instant(rs, "reported_at"),
                rs.getString("payer_name"), rs.getString("payment_reference"), rs.getString("contact"),
                rs.getString("payment_method_id"), rs.getObject("payment_details_id", Long.class), rs.getString("method_description"),
                rs.getString("display_mode"), rs.getString("public_name"), rs.getString("listing"),
                instant(rs, "decided_at"), rs.getObject("decided_by_account_id", Long.class),
                rs.getObject("confirmed_case_id", Long.class), rs.getString("confirmed_case_code"),
                rs.getObject("confirmed_amount_cents", Long.class),
                rs.getObject("received_on", LocalDate.class), rs.getString("provider_reference"), rs.getString("rejection_reason"),
                rs.getString("note"), rs.getObject("ledger_entry_id", Long.class), rs.getObject("reentry_of", Long.class),
                instant(rs, "private_details_erased_at"), instant(rs, "created_at"));
    }

    private static Tracked tracked(ResultSet rs, int row) throws SQLException {
        java.math.BigDecimal net = rs.getBigDecimal("net_cents");
        return new Tracked(rs.getLong("id"), rs.getString("public_id"), rs.getString("reference"), rs.getBytes("secret"), rs.getString("origin"),
                rs.getString("state"),
                rs.getString("reported_case_code"), rs.getObject("reported_amount_cents", Long.class), instant(rs, "reported_at"),
                instant(rs, "decided_at"), rs.getObject("confirmed_amount_cents", Long.class), rs.getString("confirmed_case_code"),
                rs.getString("rejection_reason"), net == null ? null : net.longValueExact(),
                rs.getBoolean("evidence_attached"), rs.getBoolean("evidence_ever"));
    }

    // ------------------------------------------------------------------ the donor's side

    /** Records a reported gift. Empty when the random reference is already taken: the caller draws another. */
    Optional<Long> insertReported(String publicId, String reference, byte[] secret, Long accountId, String caseCode, long amount,
                                  LocalDate paidOn, Instant now, String payerName, String paymentReference, String contact,
                                  String methodId, long detailsId, String displayMode, String publicName) {
        return jdbc.sql("""
                        insert into donation (public_id, reference, secret, origin, state, account_id,
                                              reported_case_code, reported_amount_cents, paid_on, reported_at,
                                              payer_name, payment_reference, contact, payment_method_id, payment_details_id,
                                              display_mode, public_name, created_at, updated_at)
                        values (:publicId, :reference, :secret, 'reported', 'reported', :account,
                                :caseCode, :amount, :paidOn, :now,
                                :payer, :paymentReference, :contact, :method, :details,
                                :display, :publicName, :now, :now)
                        on conflict (reference) do nothing
                        returning id
                        """)
                .param("publicId", publicId).param("reference", reference).param("secret", secret).param("account", accountId)
                .param("caseCode", caseCode).param("amount", amount).param("paidOn", paidOn).param("now", Timestamp.from(now))
                .param("payer", payerName).param("paymentReference", paymentReference).param("contact", contact)
                .param("method", methodId).param("details", detailsId).param("display", displayMode).param("publicName", publicName)
                .query(Long.class).optional();
    }

    Optional<Tracked> trackedById(long id) {
        return jdbc.sql(TRACKED + " where d.id = :id").param("id", id).query(DonationRepository::tracked).optional();
    }

    Optional<Tracked> trackedByReference(String reference) {
        return jdbc.sql(TRACKED + " where d.reference = :reference").param("reference", reference).query(DonationRepository::tracked).optional();
    }

    /** The gift that re-entered this one, if any (contract 8.3). */
    Optional<Tracked> trackedReentryOf(long id) {
        return jdbc.sql(TRACKED + " where d.reentry_of = :id").param("id", id).query(DonationRepository::tracked).optional();
    }

    /** Locks a gift's row for the rest of the transaction and returns its state, or empty when there is none. */
    Optional<String> lockState(long id) {
        return jdbc.sql("select state from donation where id = :id for update").param("id", id).query(String.class).optional();
    }

    long waitingCount() {
        return jdbc.sql("select count(*) from donation where state = 'reported'").query(Long.class).single();
    }

    // ------------------------------------------------------------------ review reads

    Optional<Gift> byPublicId(String publicId) {
        return jdbc.sql(GIFT + " where d.public_id = :id").param("id", publicId).query(DonationRepository::gift).optional();
    }

    Optional<Gift> byId(long id) {
        return jdbc.sql(GIFT + " where d.id = :id").param("id", id).query(DonationRepository::gift).optional();
    }

    /** Reads a gift and locks its row until the transaction ends: one decision on a gift at a time. */
    Optional<Gift> lockByPublicId(String publicId) {
        return jdbc.sql(GIFT + " where d.public_id = :id for update of d").param("id", publicId).query(DonationRepository::gift).optional();
    }

    Optional<String> reenteredAs(long id) {
        return jdbc.sql("select public_id from donation where reentry_of = :id").param("id", id).query(String.class).optional();
    }

    Optional<String> publicIdOf(long id) {
        return jdbc.sql("select public_id from donation where id = :id").param("id", id).query(String.class).optional();
    }

    /**
     * One page of the review list. The order is chosen from fixed text by the status filter;
     * nothing from the request reaches the SQL string.
     */
    List<Listed> list(String status, String caseCode, String reference, int limit, long offset) {
        String order = switch (status) {
            case "reported" -> "d.reported_at asc, d.public_id asc";
            case "confirmed", "rejected" -> "d.decided_at desc, d.public_id asc";
            default -> "coalesce(d.reported_at, d.created_at) desc, d.public_id asc";
        };
        return jdbc.sql(GIFT.replace("  from donation d", """
                               , (select e.amount_cents - e.corrected_cents from ledger_entry_detail e where e.id = d.ledger_entry_id) as net_cents,
                                 (select case when v.deleted_at is null then 'attached' else 'deleted' end
                                    from donation_evidence v where v.donation_id = d.id) as evidence,
                                 exists (select 1 from payment_method_details p where p.id = d.payment_details_id and p.superseded_at is not null) as superseded_details,
                                 exists (select 1 from donation o where """ + SAME_PAYMENT + """
                                 ) as possible_duplicate,
                                 coalesce((select b.allocated - b.delivered < 0 from case_balance b where b.case_id = d.confirmed_case_id), false) as shortfall,
                                 exists (select 1 from donation r where r.reentry_of = d.id) as reentered
                            from donation d
                        """) + LIST_FILTER + " order by " + order + " limit :limit offset :offset")
                .param("status", status).param("caseCode", caseCode).param("reference", reference)
                .param("limit", limit).param("offset", offset)
                .query((rs, row) -> {
                    java.math.BigDecimal net = rs.getBigDecimal("net_cents");
                    String evidence = rs.getString("evidence");
                    return new Listed(gift(rs, row), net == null ? null : net.longValueExact(), evidence == null ? "none" : evidence,
                            rs.getBoolean("superseded_details"), rs.getBoolean("possible_duplicate"), rs.getBoolean("shortfall"),
                            rs.getBoolean("reentered"));
                })
                .list();
    }

    private static final String LIST_FILTER = """
             where (:status = 'all' or d.state = :status)
               and (:caseCode::text is null or d.reported_case_code = :caseCode or d.confirmed_case_code = :caseCode)
               and (:reference::text is null or d.reference = :reference)
            """;

    long count(String status, String caseCode, String reference) {
        return jdbc.sql("select count(*) from donation d" + LIST_FILTER)
                .param("status", status).param("caseCode", caseCode).param("reference", reference).query(Long.class).single();
    }

    /** What the list row of one gift holds beside the gift, for the detail view. */
    Listed listed(long id) {
        return jdbc.sql(GIFT.replace("  from donation d", """
                               , (select e.amount_cents - e.corrected_cents from ledger_entry_detail e where e.id = d.ledger_entry_id) as net_cents,
                                 (select case when v.deleted_at is null then 'attached' else 'deleted' end
                                    from donation_evidence v where v.donation_id = d.id) as evidence,
                                 exists (select 1 from payment_method_details p where p.id = d.payment_details_id and p.superseded_at is not null) as superseded_details,
                                 exists (select 1 from donation o where """ + SAME_PAYMENT + """
                                 ) as possible_duplicate,
                                 coalesce((select b.allocated - b.delivered < 0 from case_balance b where b.case_id = d.confirmed_case_id), false) as shortfall,
                                 exists (select 1 from donation r where r.reentry_of = d.id) as reentered
                            from donation d
                        """) + " where d.id = :id")
                .param("id", id)
                .query((rs, row) -> {
                    java.math.BigDecimal net = rs.getBigDecimal("net_cents");
                    String evidence = rs.getString("evidence");
                    return new Listed(gift(rs, row), net == null ? null : net.longValueExact(), evidence == null ? "none" : evidence,
                            rs.getBoolean("superseded_details"), rs.getBoolean("possible_duplicate"), rs.getBoolean("shortfall"),
                            rs.getBoolean("reentered"));
                })
                .single();
    }

    Optional<String> ledgerPublicId(long ledgerEntryId) {
        return jdbc.sql("select public_id from ledger_entry_detail where id = :id").param("id", ledgerEntryId).query(String.class).optional();
    }

    List<Hint> possibleDuplicates(long id) {
        return jdbc.sql("""
                        select o.public_id, o.reference, o.state,
                               coalesce(o.reported_amount_cents, o.confirmed_amount_cents) as amount,
                               coalesce(o.reported_at, o.created_at) as reported_at
                          from donation d
                          join donation o on """ + SAME_PAYMENT + """
                         where d.id = :id
                         order by coalesce(o.reported_at, o.created_at) desc, o.public_id
                         limit 20
                        """)
                .param("id", id)
                .query((rs, row) -> new Hint(rs.getString("public_id"), rs.getString("reference"), rs.getString("state"),
                        rs.getLong("amount"), instant(rs, "reported_at")))
                .list();
    }

    List<CorrectionRow> corrections(long donationId) {
        return jdbc.sql("""
                        select e.public_id, c.kind, c.amount_cents, e.effective_at, c.reason, c.provider_reference
                          from donation_correction c
                          join ledger_entry_detail e on e.id = c.ledger_entry_id
                         where c.donation_id = :id
                         order by c.recorded_at, c.id
                        """)
                .param("id", donationId)
                .query((rs, row) -> new CorrectionRow(rs.getString("public_id"), rs.getString("kind"), rs.getLong("amount_cents"),
                        instant(rs, "effective_at"), rs.getString("reason"), rs.getString("provider_reference")))
                .list();
    }

    boolean hasRefund(long donationId) {
        return jdbc.sql("select exists (select 1 from donation_correction where donation_id = :id and kind = 'refund')")
                .param("id", donationId).query(Boolean.class).single();
    }

    boolean refundReferenceRecorded(long donationId, String providerReference) {
        return jdbc.sql("select exists (select 1 from donation_correction where donation_id = :id and provider_reference = :reference)")
                .param("id", donationId).param("reference", providerReference).query(Boolean.class).single();
    }

    boolean providerReferenceRecorded(String methodId, String providerReference) {
        return jdbc.sql("""
                        select exists (select 1 from donation
                                        where payment_method_id = :method and provider_reference = :reference and state = 'confirmed')
                        """)
                .param("method", methodId).param("reference", providerReference).query(Boolean.class).single();
    }

    List<HistoryRow> history(long donationId) {
        return jdbc.sql("select at, action, actor_account_id from donation_history where donation_id = :id order by at, id")
                .param("id", donationId)
                .query((rs, row) -> new HistoryRow(instant(rs, "at"), rs.getString("action"), rs.getObject("actor_account_id", Long.class)))
                .list();
    }

    // ------------------------------------------------------------------ review writes

    void history(long donationId, String action, Long actorAccountId, Instant at) {
        jdbc.sql("insert into donation_history (donation_id, at, action, actor_account_id) values (:id, :at, :action, :actor)")
                .param("id", donationId).param("at", Timestamp.from(at)).param("action", action).param("actor", actorAccountId).update();
    }

    /** {@code reported} to {@code confirmed}. False when the gift was not {@code reported}: nothing changed. */
    boolean confirm(long id, Instant now, long actorId, long caseId, String caseCode, long amount, LocalDate receivedOn,
                    String providerReference, String note, String listing, long ledgerEntryId) {
        return jdbc.sql("""
                        update donation
                           set state = 'confirmed', decided_at = :now, decided_by_account_id = :actor,
                               confirmed_case_id = :caseId, confirmed_case_code = :caseCode, confirmed_amount_cents = :amount,
                               received_on = :receivedOn, provider_reference = :providerReference, note = :note,
                               listing = :listing, public_name = case when :listing = 'named' then public_name end,
                               ledger_entry_id = :entry, updated_at = :now
                         where id = :id and state = 'reported'
                        """)
                .param("now", Timestamp.from(now)).param("actor", actorId).param("caseId", caseId).param("caseCode", caseCode)
                .param("amount", amount).param("receivedOn", receivedOn).param("providerReference", providerReference)
                .param("note", note).param("listing", listing).param("entry", ledgerEntryId).param("id", id)
                .update() == 1;
    }

    boolean reject(long id, Instant now, long actorId, String reason, String note) {
        return jdbc.sql("""
                        update donation
                           set state = 'rejected', decided_at = :now, decided_by_account_id = :actor,
                               rejection_reason = :reason, note = :note, updated_at = :now
                         where id = :id and state = 'reported'
                        """)
                .param("now", Timestamp.from(now)).param("actor", actorId).param("reason", reason).param("note", note).param("id", id)
                .update() == 1;
    }

    boolean reopen(long id, Instant now) {
        return jdbc.sql("""
                        update donation
                           set state = 'reported', decided_at = null, decided_by_account_id = null,
                               rejection_reason = null, note = null, updated_at = :now
                         where id = :id and state = 'rejected'
                        """)
                .param("now", Timestamp.from(now)).param("id", id).update() == 1;
    }

    /** A gift entered by staff: confirmed at once, with nothing reported. */
    long insertEntered(String publicId, String reference, byte[] secret, Long accountId, String payerName,
                       String methodId, String methodDescription, String displayMode, String publicName, String listing,
                       Instant now, long actorId, long caseId, String caseCode, long amount, LocalDate receivedOn,
                       String providerReference, String note, long ledgerEntryId, Long reentryOf) {
        return jdbc.sql("""
                        insert into donation (public_id, reference, secret, origin, state, account_id, payer_name,
                                              payment_method_id, method_description, display_mode, public_name, listing,
                                              decided_at, decided_by_account_id, confirmed_case_id, confirmed_case_code,
                                              confirmed_amount_cents, received_on, provider_reference, note,
                                              ledger_entry_id, reentry_of, created_at, updated_at)
                        values (:publicId, :reference, :secret, 'staff_entry', 'confirmed', :account, :payer,
                                :method, :description, :display, :publicName, :listing,
                                :now, :actor, :caseId, :caseCode,
                                :amount, :receivedOn, :providerReference, :note,
                                :entry, :reentryOf, :now, :now)
                        returning id
                        """)
                .param("publicId", publicId).param("reference", reference).param("secret", secret).param("account", accountId)
                .param("payer", payerName).param("method", methodId).param("description", methodDescription)
                .param("display", displayMode).param("publicName", publicName).param("listing", listing)
                .param("now", Timestamp.from(now)).param("actor", actorId).param("caseId", caseId).param("caseCode", caseCode)
                .param("amount", amount).param("receivedOn", receivedOn).param("providerReference", providerReference)
                .param("note", note).param("entry", ledgerEntryId).param("reentryOf", reentryOf)
                .query(Long.class).single();
    }

    boolean referenceTaken(String reference) {
        return jdbc.sql("select exists (select 1 from donation where reference = :reference)").param("reference", reference)
                .query(Boolean.class).single();
    }

    void insertCorrection(long donationId, long ledgerEntryId, String kind, long amount, String reason, LocalDate refundedOn,
                          String providerReference, String note, long actorId, Instant now) {
        jdbc.sql("""
                        insert into donation_correction (donation_id, ledger_entry_id, kind, amount_cents, reason, refunded_on,
                                                         provider_reference, note, recorded_by_account_id, recorded_at)
                        values (:donation, :entry, :kind, :amount, :reason, :refundedOn, :providerReference, :note, :actor, :now)
                        """)
                .param("donation", donationId).param("entry", ledgerEntryId).param("kind", kind).param("amount", amount)
                .param("reason", reason).param("refundedOn", refundedOn).param("providerReference", providerReference)
                .param("note", note).param("actor", actorId).param("now", Timestamp.from(now)).update();
    }

    /** Lowers the listing of a confirmed gift and drops the name it showed. */
    void restrictListing(long id, String listing, Instant now) {
        jdbc.sql("update donation set listing = :listing, public_name = null, updated_at = :now where id = :id and state = 'confirmed'")
                .param("listing", listing).param("now", Timestamp.from(now)).param("id", id).update();
    }

    // ------------------------------------------------------------------ evidence

    Optional<EvidenceRow> evidence(long donationId) {
        return jdbc.sql("select donation_id, storage_key, format, bytes, deleted_at from donation_evidence where donation_id = :id")
                .param("id", donationId)
                .query((rs, row) -> new EvidenceRow(rs.getLong("donation_id"), rs.getString("storage_key"), rs.getString("format"),
                        rs.getLong("bytes"), instant(rs, "deleted_at")))
                .optional();
    }

    /** Attaches the one screenshot of a gift. False when the gift already has, or ever had, one: the token is spent. */
    boolean attachEvidence(long donationId, String storageKey, String format, int width, int height, long bytes, byte[] sha256, Instant now) {
        return jdbc.sql("""
                        insert into donation_evidence (donation_id, storage_key, format, width, height, bytes, sha256, uploaded_at)
                        values (:id, :key, :format, :width, :height, :bytes, :sha, :now)
                        on conflict (donation_id) do nothing
                        """)
                .param("id", donationId).param("key", storageKey).param("format", format).param("width", width).param("height", height)
                .param("bytes", bytes).param("sha", sha256).param("now", Timestamp.from(now)).update() == 1;
    }

    /** Marks the screenshot of a gift deleted. False when there is none or it is already deleted. */
    boolean markEvidenceDeleted(long donationId, Instant now) {
        return jdbc.sql("update donation_evidence set deleted_at = :now where donation_id = :id and deleted_at is null")
                .param("now", Timestamp.from(now)).param("id", donationId).update() == 1;
    }

    long evidenceBytesStored() {
        return jdbc.sql("select coalesce(sum(bytes), 0) from donation_evidence where deleted_at is null").query(Long.class).single();
    }

    // ------------------------------------------------------------------ retention

    /** Gifts decided before the instant whose screenshot is still stored. */
    List<Long> evidenceDue(Instant decidedBefore, int atMost) {
        return jdbc.sql("""
                        select d.id from donation d
                          join donation_evidence v on v.donation_id = d.id and v.deleted_at is null
                         where d.state <> 'reported' and d.decided_at < :before
                         order by d.decided_at limit :atMost
                        """)
                .param("before", Timestamp.from(decidedBefore)).param("atMost", atMost).query(Long.class).list();
    }

    /** Gifts whose private details are due to be erased: rejected or confirmed before the instant of its state. */
    List<Long> privateDetailsDue(Instant rejectedBefore, Instant confirmedBefore, int atMost) {
        return jdbc.sql("""
                        select d.id from donation d
                         where d.private_details_erased_at is null
                           and ((d.state = 'rejected' and d.decided_at < :rejectedBefore)
                             or (d.state = 'confirmed' and d.decided_at < :confirmedBefore))
                         order by d.decided_at limit :atMost
                        """)
                .param("rejectedBefore", Timestamp.from(rejectedBefore)).param("confirmedBefore", Timestamp.from(confirmedBefore))
                .param("atMost", atMost).query(Long.class).list();
    }

    /**
     * Erases the private details of a gift that is still due, and returns which fields held
     * something. Empty when the gift was reopened or erased meanwhile: nothing changed.
     */
    Optional<List<String>> erasePrivateDetails(long id, Instant rejectedBefore, Instant confirmedBefore, Instant now) {
        Optional<List<String>> held = jdbc.sql("""
                        select payer_name is not null as payer_name, payment_reference is not null as payment_reference,
                               contact is not null as contact, note is not null as note,
                               exists (select 1 from donation_correction c where c.donation_id = d.id and c.note is not null) as correction_notes
                          from donation d
                         where d.id = :id and d.private_details_erased_at is null
                           and ((d.state = 'rejected' and d.decided_at < :rejectedBefore)
                             or (d.state = 'confirmed' and d.decided_at < :confirmedBefore))
                           for update of d
                        """)
                .param("id", id).param("rejectedBefore", Timestamp.from(rejectedBefore)).param("confirmedBefore", Timestamp.from(confirmedBefore))
                .query((rs, row) -> {
                    List<String> fields = new java.util.ArrayList<>();
                    for (String field : List.of("payer_name", "payment_reference", "contact", "note", "correction_notes")) {
                        if (rs.getBoolean(field)) {
                            fields.add(field);
                        }
                    }
                    return List.copyOf(fields);
                })
                .optional();
        if (held.isEmpty()) {
            return held;
        }
        jdbc.sql("""
                        update donation
                           set payer_name = null, payment_reference = null, contact = null, note = null,
                               private_details_erased_at = :now, updated_at = :now
                         where id = :id
                        """)
                .param("now", Timestamp.from(now)).param("id", id).update();
        jdbc.sql("update donation_correction set note = null where donation_id = :id and note is not null").param("id", id).update();
        return held;
    }
}
