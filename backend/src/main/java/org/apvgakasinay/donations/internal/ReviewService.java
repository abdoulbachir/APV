package org.apvgakasinay.donations.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apvgakasinay.cases.CaseResolver;
import org.apvgakasinay.cases.CaseStatusService;
import org.apvgakasinay.cases.CaseStatusService.StatusOutcome;
import org.apvgakasinay.cases.ResolvedCase;
import org.apvgakasinay.donations.internal.DonationRepository.EvidenceRow;
import org.apvgakasinay.donations.internal.DonationRepository.Gift;
import org.apvgakasinay.donations.internal.DonationRepository.HistoryRow;
import org.apvgakasinay.donations.internal.DonationRepository.Listed;
import org.apvgakasinay.donations.internal.PaymentMethodRepository.Details;
import org.apvgakasinay.identity.StaffAccess;
import org.apvgakasinay.identity.StaffAccess.Actor;
import org.apvgakasinay.ledger.CorrectionKind;
import org.apvgakasinay.ledger.DonorListing;
import org.apvgakasinay.ledger.LedgerWriteService;
import org.apvgakasinay.ledger.ListingVisibility;
import org.apvgakasinay.ledger.RecordedCorrection;
import org.apvgakasinay.ledger.RecordedDonation;
import org.apvgakasinay.shared.ApiCaller;
import org.apvgakasinay.shared.ApiException;
import org.apvgakasinay.shared.Page;
import org.apvgakasinay.shared.PageRequest;
import org.apvgakasinay.shared.Timestamps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * What a super administrator does with gifts (contract/donation-lifecycle.md, sections 6 to 8):
 * read the queue, confirm, reject, reopen, enter a gift that arrived without a report, correct
 * a confirmed gift, and show less of a donor.
 *
 * <p>Every operation is one transaction and begins the same way, in the contract's order: the
 * account is read again under a shared lock (11.3), the account's counter is advanced, a repeated
 * idempotency key is answered, and only then is the gift looked at, under a lock on its row.
 * Locks are always taken in the order gift, ledger entry, case, platform total. Whatever a
 * decision causes (the ledger entry, the donor listing, the case status, the audit record)
 * commits with it or not at all.
 */
@Service
@Transactional
public class ReviewService {

    static final String PERMISSION = "donations.confirm";
    private static final Logger LOG = LoggerFactory.getLogger(ReviewService.class);

    public record Confirm(String caseCode, long amount, LocalDate receivedOn, String providerReference, String listing, String note) {
    }

    public record Reject(String reason, String note) {
    }

    public record Entry(String caseCode, long amount, LocalDate receivedOn, String providerReference, String methodId,
                        String methodDescription, String payerName, String displayMode, String publicName, String note, String reentryOf) {
    }

    public record Reversal(long amount, String reason, String note) {
    }

    public record Refund(long amount, LocalDate refundedOn, String providerReference, String note) {
    }

    /** A stored screenshot, as it is served. */
    public record Screenshot(byte[] bytes, String format, String fileName) {
    }

    private final DonationRepository gifts;
    private final PaymentMethodRepository methods;
    private final CaseResolver cases;
    private final CaseStatusService caseStatus;
    private final LedgerWriteService ledger;
    private final StaffAccess staff;
    private final Idempotency idempotency;
    private final DonationThrottle throttle;
    private final EvidenceStore store;
    private final OrganizationCalendar calendar;
    private final DonationAudit audit;
    private final DonationClock clock;

    ReviewService(DonationRepository gifts, PaymentMethodRepository methods, CaseResolver cases, CaseStatusService caseStatus,
                  LedgerWriteService ledger, StaffAccess staff, Idempotency idempotency, DonationThrottle throttle, EvidenceStore store,
                  OrganizationCalendar calendar, DonationAudit audit, DonationClock clock) {
        this.gifts = gifts;
        this.methods = methods;
        this.cases = cases;
        this.caseStatus = caseStatus;
        this.ledger = ledger;
        this.staff = staff;
        this.idempotency = idempotency;
        this.throttle = throttle;
        this.store = store;
        this.calendar = calendar;
        this.audit = audit;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ reads

    public Views.DonationPage list(String status, String caseCode, String reference, PageRequest page, ApiCaller caller) {
        reader(caller);
        List<Views.ListItem> items = gifts.list(status, caseCode, reference, page.pageSize(), page.offset()).stream()
                .map(ReviewService::listItem).toList();
        Page<Views.ListItem> paged = Page.of(items, page, gifts.count(status, caseCode, reference));
        return new Views.DonationPage(paged.items(), paged.page(), paged.pageSize(), paged.totalItems(), paged.totalPages(), gifts.waitingCount());
    }

    public Views.Detail detail(String donationId, ApiCaller caller) {
        reader(caller);
        return detail(gifts.byPublicId(donationId).orElseThrow(ReviewService::notFound).id());
    }

    /** The stored screenshot of a gift. Every read is written to the audit log (4.4). */
    public Screenshot evidence(String donationId, ApiCaller caller) {
        Actor actor = reader(caller);
        Gift gift = gifts.byPublicId(donationId).orElseThrow(ReviewService::notFound);
        EvidenceRow row = gifts.evidence(gift.id()).filter(found -> found.deletedAt() == null).orElseThrow(ReviewService::noEvidence);
        byte[] bytes = store.available() ? store.read(row.storageKey()).orElse(null) : null;
        if (bytes == null) {
            LOG.error("The stored screenshot of gift {} is missing from the evidence directory", gift.publicId());
            throw noEvidence();
        }
        audit.noted("donation.evidence_read", caller, actor.id(), DonationAudit.details().gift(gift.publicId()));
        return new Screenshot(bytes, row.format(), "evidence-" + gift.reference() + (row.format().equals("jpeg") ? ".jpg" : ".png"));
    }

    // ------------------------------------------------------------------ decisions

    /** {@code reported} to {@code confirmed}, with one ledger entry, the donor listing and the case status, in this transaction (6.3). */
    public Views.Detail confirm(String donationId, Confirm request, String idempotencyKey, byte[] body, ApiCaller caller) {
        Actor actor = writer(caller);
        Instant now = clock.now();
        // Validation that needs the database: the case exists and is not a draft; the day is plausible.
        ResolvedCase aidCase = cases.resolve(request.caseCode())
                .orElseThrow(() -> ApiException.invalid("caseCode", "invalid_value", "caseCode does not name a published case"));
        received(request.receivedOn(), "receivedOn");

        Optional<Views.Detail> earlier = repeated(actor, "confirm", donationId, idempotencyKey, body);
        if (earlier.isPresent()) {
            return earlier.get();
        }
        Gift gift = gifts.lockByPublicId(donationId).orElseThrow(ReviewService::notFound);
        if (!gift.state().equals("reported")) {
            throw refused(gift, actor, caller, "state", stateConflict());
        }
        if (!aidCase.code().equals(gift.reportedCaseCode()) && request.note() == null) {
            throw ApiException.invalid("note", "required", "note is required when the case is not the reported one");
        }
        if (request.providerReference() != null && gift.methodId() != null
                && gifts.providerReferenceRecorded(gift.methodId(), request.providerReference())) {
            throw refused(gift, actor, caller, "duplicate_payment_reference", duplicateReference());
        }
        // Never more than the donor asked: the reviewer's choice can only show less (6.3).
        String listing = lesser(gift.displayMode(), request.listing().equals("as_requested") ? gift.displayMode() : request.listing());
        RecordedDonation entry = record(gift, actor, caller, aidCase, request.amount(), now, listing, gift.publicName());
        try {
            if (!gifts.confirm(gift.id(), now, actor.id(), aidCase.id(), aidCase.code(), request.amount(), request.receivedOn(),
                    request.providerReference(), request.note(), listing, entry.entryId())) {
                throw stateConflict();
            }
        } catch (DuplicateKeyException sameReference) {
            // Two confirmations with one provider reference at the same moment: the unique index decides.
            throw refused(gift, actor, caller, "duplicate_payment_reference", duplicateReference());
        }
        gifts.history(gift.id(), "confirmed", actor.id(), now);
        audit.changed("donation.confirmed", caller, actor.id(), DonationAudit.details().gift(gift.publicId())
                .put("reported_amount", gift.reportedAmount()).put("confirmed_amount", request.amount())
                .put("reported_case", gift.reportedCaseCode()).put("confirmed_case", aidCase.code())
                .put("ledger_entry", entry.entryPublicId()).put("listing", listing)
                .put("amount_differs", gift.reportedAmount() != request.amount())
                .put("case_differs", !aidCase.code().equals(gift.reportedCaseCode())));
        afterMoney(aidCase.id(), entry.entryPublicId(), caller, actor);
        return answered(actor, idempotencyKey, 200, gift.id());
    }

    public Views.Detail reject(String donationId, Reject request, ApiCaller caller) {
        Actor actor = writer(caller);
        Instant now = clock.now();
        Gift gift = gifts.lockByPublicId(donationId).orElseThrow(ReviewService::notFound);
        if (!gift.state().equals("reported") || !gifts.reject(gift.id(), now, actor.id(), request.reason(), request.note())) {
            throw stateConflict();
        }
        gifts.history(gift.id(), "rejected", actor.id(), now);
        audit.changed("donation.rejected", caller, actor.id(), DonationAudit.details().gift(gift.publicId()).put("reason", request.reason()));
        return detail(gift.id());
    }

    /** {@code rejected} back to {@code reported}. The rejection stays in the history; its retention period starts again (14.1). */
    public Views.Detail reopen(String donationId, ApiCaller caller) {
        Actor actor = writer(caller);
        Instant now = clock.now();
        Gift gift = gifts.lockByPublicId(donationId).orElseThrow(ReviewService::notFound);
        if (!gift.state().equals("rejected") || !gifts.reopen(gift.id(), now)) {
            throw stateConflict();
        }
        gifts.history(gift.id(), "reopened", actor.id(), now);
        audit.changed("donation.reopened", caller, actor.id(), DonationAudit.details().gift(gift.publicId()).put("reason", gift.rejectionReason()));
        return detail(gift.id());
    }

    /** A gift that arrived without a report: recorded and confirmed at once (section 7), or the re-entry of a reversed gift (8.3). */
    public Views.Detail enter(Entry request, String idempotencyKey, byte[] body, ApiCaller caller) {
        Actor actor = writer(caller);
        Instant now = clock.now();
        ResolvedCase aidCase = cases.resolve(request.caseCode())
                .orElseThrow(() -> ApiException.invalid("caseCode", "invalid_value", "caseCode does not name a published case"));
        received(request.receivedOn(), "receivedOn");
        if (request.methodId() != null && methods.method(request.methodId()).isEmpty()) {
            throw ApiException.invalid("method", "invalid_value", "method.id does not name a payment method");
        }

        Optional<Views.Detail> earlier = repeated(actor, "enter", "", idempotencyKey, body);
        if (earlier.isPresent()) {
            return earlier.get();
        }

        Gift original = null;
        String displayMode = request.displayMode();
        String publicName = request.publicName();
        if (request.reentryOf() != null) {
            original = gifts.lockByPublicId(request.reentryOf()).orElse(null);
            if (original == null || !reentryAllowed(original)) {
                throw refused(original, actor, caller, "reentry_not_allowed",
                        ApiException.of(HttpStatus.CONFLICT, "reentry_not_allowed", "The gift named by reentryOf cannot be re-entered"));
            }
            // The re-entry takes over how the donor is shown: their choice, and never more than is shown now.
            displayMode = lesser(original.listing(), request.displayMode());
            publicName = displayMode.equals("named") ? original.publicName() : null;
        }
        if (request.providerReference() != null && request.methodId() != null
                && gifts.providerReferenceRecorded(request.methodId(), request.providerReference())) {
            throw refused(original, actor, caller, "duplicate_payment_reference", duplicateReference());
        }

        String publicId = Codes.publicId("don_");
        String reference = Codes.newReference();
        for (int attempt = 0; attempt < 8 && gifts.referenceTaken(reference); attempt++) {
            reference = Codes.newReference();
        }
        RecordedDonation entry;
        try {
            entry = ledger.recordDonation(aidCase.id(), request.amount(), now, donorListing(displayMode, publicName));
        } catch (ApiException limit) {
            throw refused(original, actor, caller, limit.code(), limit);
        }
        long id;
        try {
            id = gifts.insertEntered(publicId, reference, Codes.random(32), original == null ? null : original.accountId(),
                    request.payerName(), request.methodId(), request.methodDescription(),
                    original == null ? request.displayMode() : original.displayMode(), publicName, displayMode,
                    now, actor.id(), aidCase.id(), aidCase.code(), request.amount(), request.receivedOn(), request.providerReference(),
                    request.note(), entry.entryId(), original == null ? null : original.id());
        } catch (DuplicateKeyException sameReference) {
            throw refused(original, actor, caller, "duplicate_payment_reference", duplicateReference());
        }
        gifts.history(id, "entered", actor.id(), now);
        audit.changed("donation.entered", caller, actor.id(), DonationAudit.details().gift(publicId)
                .put("case", aidCase.code()).put("amount", request.amount())
                .put("method", request.methodId() == null ? "described" : request.methodId())
                .put("ledger_entry", entry.entryPublicId()).put("reentry_of", original == null ? null : original.publicId()));
        afterMoney(aidCase.id(), entry.entryPublicId(), caller, actor);
        return answered(actor, idempotencyKey, 201, id);
    }

    /**
     * A re-entry replaces a confirmed gift of which nothing still stands, corrected by reversals
     * only (refunded money is gone), and not re-entered already, so one payment is counted once.
     */
    private boolean reentryAllowed(Gift original) {
        return original.confirmed()
                && ledger.remaining(original.ledgerEntryId()) == 0
                && !gifts.hasRefund(original.id())
                && gifts.reenteredAs(original.id()).isEmpty();
    }

    // ------------------------------------------------------------------ corrections

    /** Repairs a record: no money moved (8.1). */
    public Views.Detail reverse(String donationId, Reversal request, String idempotencyKey, byte[] body, ApiCaller caller) {
        return correct(donationId, CorrectionKind.DONATION_REVERSAL, "reverse", request.amount(), request.reason(), null, null,
                request.note(), idempotencyKey, body, caller);
    }

    /** Records money that really went back to the donor, outside the site (8.1). */
    public Views.Detail refund(String donationId, Refund request, String idempotencyKey, byte[] body, ApiCaller caller) {
        return correct(donationId, CorrectionKind.REFUND, "refund", request.amount(), null, request.refundedOn(), request.providerReference(),
                request.note(), idempotencyKey, body, caller);
    }

    private Views.Detail correct(String donationId, CorrectionKind kind, String action, long amount, String reason, LocalDate refundedOn,
                                 String providerReference, String note, String idempotencyKey, byte[] body, ApiCaller caller) {
        Actor actor = writer(caller);
        Instant now = clock.now();
        if (refundedOn != null) {
            received(refundedOn, "refundedOn");
        }
        Optional<Views.Detail> earlier = repeated(actor, action, donationId, idempotencyKey, body);
        if (earlier.isPresent()) {
            return earlier.get();
        }
        Gift gift = gifts.lockByPublicId(donationId).orElseThrow(ReviewService::notFound);
        if (!gift.confirmed()) {
            throw refused(gift, actor, caller, "state", stateConflict());
        }
        if (providerReference != null && gifts.refundReferenceRecorded(gift.id(), providerReference)) {
            throw refused(gift, actor, caller, "duplicate_payment_reference", duplicateReference());
        }
        // The ledger locks the gift's entry and checks what still stands under that lock (8.2).
        RecordedCorrection entry;
        try {
            entry = ledger.recordCorrection(gift.ledgerEntryId(), kind, amount, now);
        } catch (ApiException limit) {
            throw refused(gift, actor, caller, limit.code(), limit);
        }
        gifts.insertCorrection(gift.id(), entry.entryId(), kind.stored(), amount, reason, refundedOn, providerReference, note, actor.id(), now);
        gifts.history(gift.id(), kind == CorrectionKind.REFUND ? "refunded" : "reversed", actor.id(), now);
        audit.changed(kind == CorrectionKind.REFUND ? "donation.refunded" : "donation.reversed", caller, actor.id(),
                DonationAudit.details().gift(gift.publicId()).put("amount", amount).put("reason", reason)
                        .put("ledger_entry", entry.entryPublicId()).put("remaining", entry.remainingCents()));
        afterMoney(gift.confirmedCaseId(), entry.entryPublicId(), caller, actor);
        return answered(actor, idempotencyKey, 200, gift.id());
    }

    /** Shows less of the donor of a confirmed gift (8.4). No amount, total or ledger entry changes. */
    public Views.Detail restrictListing(String donationId, String listing, ApiCaller caller) {
        Actor actor = writer(caller);
        Instant now = clock.now();
        Gift gift = gifts.lockByPublicId(donationId).orElseThrow(ReviewService::notFound);
        // It can only show less than is shown now: the same or more is refused.
        if (!gift.confirmed() || rank(listing) >= rank(gift.listing())) {
            throw stateConflict();
        }
        ledger.restrictListing(gift.ledgerEntryId(), listing.equals("hidden") ? ListingVisibility.HIDDEN : ListingVisibility.ANONYMOUS);
        gifts.restrictListing(gift.id(), listing, now);
        gifts.history(gift.id(), "listing_restricted", actor.id(), now);
        audit.changed("donation.listing_restricted", caller, actor.id(),
                DonationAudit.details().gift(gift.publicId()).put("from", gift.listing()).put("to", listing));
        return detail(gift.id());
    }

    /** Deletes the screenshot of a gift before its time, for a privacy request (4.4). The gift keeps that one existed. */
    public Views.Detail deleteEvidence(String donationId, ApiCaller caller) {
        Actor actor = writer(caller);
        Instant now = clock.now();
        Gift gift = gifts.lockByPublicId(donationId).orElseThrow(ReviewService::notFound);
        EvidenceRow row = gifts.evidence(gift.id()).filter(found -> found.deletedAt() == null).orElseThrow(ReviewService::noEvidence);
        if (!gifts.markEvidenceDeleted(gift.id(), now)) {
            throw noEvidence();
        }
        gifts.history(gift.id(), "evidence_deleted", actor.id(), now);
        audit.changed("donation.evidence_deleted", caller, actor.id(), DonationAudit.details().gift(gift.publicId()).put("reason", "privacy_request"));
        deleteFileAfterCommit(store, row.storageKey());
        return detail(gift.id());
    }

    /** The file goes once the row says it is gone, never before: a rolled-back deletion keeps its file. */
    static void deleteFileAfterCommit(EvidenceStore store, String storageKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    if (store.available()) {
                        store.delete(storageKey);
                    }
                } catch (RuntimeException failure) {
                    LOG.error("A screenshot marked deleted could not be removed from the evidence directory: {}", failure.getClass().getSimpleName());
                }
            }
        });
    }

    // ------------------------------------------------------------------ what every operation shares

    private Actor reader(ApiCaller caller) {
        Actor actor = staff.require(caller, PERMISSION);
        throttle.attempt(DonationThrottle.STAFF_READS_BY_ACCOUNT, String.valueOf(actor.id()));
        return actor;
    }

    private Actor writer(ApiCaller caller) {
        Actor actor = staff.require(caller, PERMISSION);
        throttle.attempt(DonationThrottle.STAFF_WRITES_BY_ACCOUNT, String.valueOf(actor.id()));
        return actor;
    }

    /** The answer of an earlier request with this key, when this is the same request again (6.6). */
    private Optional<Views.Detail> repeated(Actor actor, String action, String target, String idempotencyKey, byte[] body) {
        return idempotency.claim(Idempotency.staffScope(actor.id()), Idempotency.STAFF_OPERATION, idempotencyKey,
                        idempotency.requestHash(action, target, body), Idempotency.STAFF_WINDOW)
                .map(earlier -> detail(earlier.donationId()));
    }

    private Views.Detail answered(Actor actor, String idempotencyKey, int status, long donationId) {
        idempotency.complete(Idempotency.staffScope(actor.id()), Idempotency.STAFF_OPERATION, idempotencyKey, status, donationId);
        return detail(donationId);
    }

    /** Writes the one donation entry of a gift, with its listing. A write past the platform bound is refused and recorded as such. */
    private RecordedDonation record(Gift gift, Actor actor, ApiCaller caller, ResolvedCase aidCase, long amount, Instant now,
                                    String listing, String publicName) {
        try {
            return ledger.recordDonation(aidCase.id(), amount, now, donorListing(listing, publicName));
        } catch (ApiException limit) {
            throw refused(gift, actor, caller, limit.code(), limit);
        }
    }

    private static DonorListing donorListing(String listing, String publicName) {
        return switch (listing) {
            case "named" -> DonorListing.named(publicName);
            case "anonymous" -> DonorListing.anonymous();
            default -> DonorListing.hidden();
        };
    }

    /** The status of the case follows the money, in this transaction; what it did is recorded (9.3). */
    private void afterMoney(long caseId, String ledgerEntry, ApiCaller caller, Actor actor) {
        StatusOutcome outcome = caseStatus.afterMoneyMoved(caseId);
        if (outcome.changed()) {
            audit.changed("case.status_changed", caller, actor.id(), DonationAudit.details().put("case", outcome.caseCode())
                    .put("from", outcome.before()).put("to", outcome.after()).put("ledger_entry", ledgerEntry));
        } else if (outcome.reviewNeeded()) {
            audit.changed("case.review_needed", caller, actor.id(), DonationAudit.details().put("case", outcome.caseCode())
                    .put("status", outcome.after()).put("ledger_entry", ledgerEntry));
        }
    }

    /** A confirmation or a correction refused by a rule is recorded, whatever happens to the request (13). */
    private ApiException refused(Gift gift, Actor actor, ApiCaller caller, String rule, ApiException refusal) {
        audit.refused("donation.decision_refused", caller, actor.id(),
                DonationAudit.details().gift(gift == null ? null : gift.publicId()).put("rule", rule));
        return refusal;
    }

    private void received(LocalDate day, String field) {
        LocalDate today = calendar.today();
        if (day.isAfter(today.plusDays(1)) || day.isBefore(today.minusDays(366))) {
            throw ApiException.invalid(field, "out_of_range", field + " must be within the last 366 days");
        }
    }

    private static int rank(String listing) {
        return switch (listing) {
            case "named" -> 2;
            case "anonymous" -> 1;
            default -> 0;
        };
    }

    /** Of two ways to be listed, the one that shows less. */
    private static String lesser(String one, String other) {
        return rank(one) <= rank(other) ? one : other;
    }

    private static ApiException notFound() {
        return ApiException.of(HttpStatus.NOT_FOUND, "donation_not_found", "Gift not found");
    }

    private static ApiException noEvidence() {
        return ApiException.of(HttpStatus.NOT_FOUND, "evidence_not_found", "No screenshot for this gift");
    }

    private static ApiException stateConflict() {
        return ApiException.of(HttpStatus.CONFLICT, "donation_state_conflict", "The gift is not in the required state");
    }

    private static ApiException duplicateReference() {
        return ApiException.of(HttpStatus.CONFLICT, "duplicate_payment_reference", "That provider reference is already recorded");
    }

    // ------------------------------------------------------------------ views

    private static List<String> flags(Listed row) {
        Gift gift = row.gift();
        List<String> flags = new ArrayList<>();
        if (row.supersededDetails()) {
            flags.add("superseded_details");
        }
        if (row.possibleDuplicate()) {
            flags.add("possible_duplicate");
        }
        if (row.shortfall()) {
            flags.add("shortfall");
        }
        if (row.reentered()) {
            flags.add("reentered");
        }
        if (gift.confirmedCaseCode() != null && gift.reportedCaseCode() != null && !gift.confirmedCaseCode().equals(gift.reportedCaseCode())) {
            flags.add("different_case");
        }
        return flags;
    }

    private static Views.ListItem listItem(Listed row) {
        Gift gift = row.gift();
        return new Views.ListItem(gift.publicId(), gift.reference(), gift.origin(), gift.state(),
                gift.confirmedCaseCode() != null ? gift.confirmedCaseCode() : gift.reportedCaseCode(),
                gift.reportedAmount(), gift.confirmedAmount(), row.netAmount(), gift.methodId(), gift.payerName(),
                Timestamps.format(gift.reportedAt() != null ? gift.reportedAt() : gift.createdAt()), Timestamps.format(gift.decidedAt()),
                row.evidence(), flags(row));
    }

    /** Everything recorded about one gift, as the review screens show it. */
    private Views.Detail detail(long id) {
        Listed row = gifts.listed(id);
        Gift gift = row.gift();
        List<HistoryRow> history = gifts.history(id);
        Set<Long> accountIds = new HashSet<>();
        history.forEach(entry -> accountIds.add(entry.actorAccountId()));
        accountIds.add(gift.decidedBy());
        accountIds.remove(null);
        Map<Long, Actor> actors = staff.actors(accountIds);

        Views.ShownDetails shown = null;
        if (gift.detailsId() != null) {
            Details details = methods.detailsById(gift.detailsId()).orElseThrow();
            shown = new Views.ShownDetails(details.publicId(), details.revision(), details.supersededAt() == null,
                    Timestamps.format(details.supersededAt()), details.recipient());
        }
        Views.Decision decision = gift.decidedAt() == null ? null
                : new Views.Decision(gift.state(), Timestamps.format(gift.decidedAt()), actor(actors, gift.decidedBy()),
                        gift.confirmedCaseCode(), gift.confirmedAmount(), gift.receivedOn() == null ? null : gift.receivedOn().toString(),
                        gift.providerReference(), gift.listing(), gift.rejectionReason(), gift.note());
        return new Views.Detail(gift.publicId(), gift.reference(), gift.origin(), gift.state(),
                gift.reportedAt() == null ? null
                        : new Views.Reported(gift.reportedCaseCode(), gift.reportedAmount(), gift.paidOn().toString(), Timestamps.format(gift.reportedAt())),
                new Views.MethodRef(gift.methodId(), gift.methodDescription()),
                shown, gift.payerName(), gift.paymentReference(), gift.contact(),
                new Views.DisplayState(gift.listing() != null ? gift.listing() : gift.displayMode(), gift.publicName()),
                gift.accountId() != null, row.evidence(), flags(row), decision,
                gift.ledgerEntryId() == null ? null : gifts.ledgerPublicId(gift.ledgerEntryId()).orElse(null),
                row.netAmount(),
                gifts.corrections(id).stream().map(correction -> new Views.Correction(correction.ledgerPublicId(), correction.kind(),
                        correction.amount(), Timestamps.format(correction.at()), correction.reason(), correction.providerReference())).toList(),
                gift.reentryOf() == null ? null : gifts.publicIdOf(gift.reentryOf()).orElse(null),
                gifts.reenteredAs(id).orElse(null),
                gifts.possibleDuplicates(id).stream().map(hint -> new Views.DuplicateHint(hint.publicId(), hint.reference(), hint.state(),
                        hint.amount(), Timestamps.format(hint.reportedAt()))).toList(),
                history.stream().map(entry -> new Views.HistoryEntry(Timestamps.format(entry.at()), entry.action(),
                        actor(actors, entry.actorAccountId()))).toList(),
                Timestamps.format(gift.erasedAt()));
    }

    private static Views.StaffActor actor(Map<Long, Actor> actors, Long accountId) {
        Actor actor = accountId == null ? null : actors.get(accountId);
        return actor == null ? null : new Views.StaffActor(actor.publicId(), actor.displayName());
    }
}
