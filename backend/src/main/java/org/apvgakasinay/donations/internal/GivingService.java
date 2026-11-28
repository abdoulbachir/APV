package org.apvgakasinay.donations.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.apvgakasinay.cases.CaseResolver;
import org.apvgakasinay.cases.ResolvedCase;
import org.apvgakasinay.donations.internal.DonationRepository.Tracked;
import org.apvgakasinay.donations.internal.PaymentMethodRepository.Details;
import org.apvgakasinay.donations.internal.PaymentMethodRepository.Method;
import org.apvgakasinay.donations.internal.PaymentMethodRepository.ShownDetails;
import org.apvgakasinay.shared.ApiCaller;
import org.apvgakasinay.shared.ApiException;
import org.apvgakasinay.shared.Timestamps;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a donor does (contract/donation-lifecycle.md, sections 2, 3 and 5): read the payment
 * methods, report a gift paid outside the site, and follow that one gift with its tracking code.
 * No account is needed for any of it, and none of it changes anything public.
 */
@Service
@Transactional
public class GivingService {

    static final Duration EVIDENCE_WINDOW = Duration.ofMinutes(30);
    /** Recipient details replaced, or a method deactivated, this long ago are no longer accepted in a report (2.4). */
    static final Duration DETAILS_GRACE = Duration.ofHours(24);
    private static final List<String> EVIDENCE_FORMATS = List.of("image/jpeg", "image/png");
    private static final byte[] NO_SECRET = new byte[32];

    /** A report as the controller validated it: every value is well formed; whether it names real things is decided here. */
    public record Report(String caseCode, long amount, String detailsId, LocalDate paidOn, String payerName, String paymentReference,
                         String contact, String displayMode, String publicName) {
    }

    private final DonationRepository gifts;
    private final PaymentMethodRepository methods;
    private final CaseResolver cases;
    private final OrganizationCalendar calendar;
    private final Idempotency idempotency;
    private final Brakes brakes;
    private final DonationThrottle throttle;
    private final DonationKeys keys;
    private final DonationAudit audit;
    private final DonationClock clock;
    private final DonationSettings settings;

    GivingService(DonationRepository gifts, PaymentMethodRepository methods, CaseResolver cases, OrganizationCalendar calendar, Idempotency idempotency,
                  Brakes brakes, DonationThrottle throttle, DonationKeys keys, DonationAudit audit, DonationClock clock,
                  DonationSettings settings) {
        this.gifts = gifts;
        this.methods = methods;
        this.cases = cases;
        this.calendar = calendar;
        this.idempotency = idempotency;
        this.brakes = brakes;
        this.throttle = throttle;
        this.keys = keys;
        this.audit = audit;
        this.clock = clock;
        this.settings = settings;
    }

    /** The active methods in display order, each with its current details version. Empty: giving is not open. */
    @Transactional(readOnly = true)
    public Views.PaymentMethodList methods() {
        return new Views.PaymentMethodList(methods.activeWithCurrentDetails().stream().map(GivingService::view).toList());
    }

    private static Views.PaymentMethod view(ShownDetails shown) {
        Method method = shown.method();
        Details details = shown.details();
        return new Views.PaymentMethod(method.id(), new PaymentMethodRepository.Localized(method.nameEn(), method.nameFr()),
                method.position(), details.publicId(), details.instructions(), details.recipient());
    }

    /**
     * Records a reported gift and returns its receipt. Everything is one transaction: a refused
     * report leaves nothing, and its idempotency key was not consumed.
     *
     * @param body the bytes of the request body, by which a repeated request is recognised
     */
    public Views.Receipt report(Report report, String idempotencyKey, byte[] body, ApiCaller caller) {
        Instant now = clock.now();

        // Step 7, the part of validation that needs the database: do the case and the details exist?
        // An unknown code and a draft are answered alike.
        ResolvedCase aidCase = cases.resolve(report.caseCode())
                .orElseThrow(() -> ApiException.invalid("caseCode", "invalid_value", "caseCode does not name a case"));
        Details details = methods.detailsByPublicId(report.detailsId())
                .orElseThrow(() -> ApiException.invalid("paymentDetailsId", "invalid_value", "paymentDetailsId does not name recipient details"));
        LocalDate today = calendar.today();
        if (report.paidOn().isBefore(today.minusDays(90)) || report.paidOn().isAfter(today.plusDays(1))) {
            throw ApiException.invalid("paidOn", "out_of_range", "paidOn must be within the last 90 days");
        }

        // Step 8. A repeated key first: the same request is answered as it was, whatever has changed since.
        byte[] requestHash = idempotency.requestHash("report", "", body);
        Optional<Idempotency.Earlier> earlier = idempotency.claim(Idempotency.REPORT_SCOPE, Idempotency.REPORT_OPERATION, idempotencyKey,
                requestHash, Idempotency.REPORT_WINDOW);
        if (earlier.isPresent()) {
            return receipt(gifts.trackedById(earlier.get().donationId()).orElseThrow());
        }

        // The rules of the operation.
        if (!aidCase.accepting()) {
            throw ApiException.of(HttpStatus.CONFLICT, "case_not_accepting", "This case does not accept gifts now");
        }
        Method method = methods.method(details.methodId()).orElseThrow();
        if (noLongerShown(details, method, now)) {
            throw ApiException.of(HttpStatus.CONFLICT, "payment_details_changed", "The recipient details have changed");
        }

        // The brakes of the whole site, last: counted before the report is recorded.
        brakes.beforeReport(caller);

        String publicId = Codes.publicId("don_");
        byte[] secret = Codes.random(32);
        Long id = null;
        String reference = null;
        for (int attempt = 0; attempt < 8 && id == null; attempt++) {
            reference = Codes.newReference();
            id = gifts.insertReported(publicId, reference, secret, caller.accountId(), aidCase.code(), report.amount(), report.paidOn(), now,
                    report.payerName(), report.paymentReference(), report.contact(), method.id(), details.id(),
                    report.displayMode(), report.publicName()).orElse(null);
        }
        if (id == null) {
            throw new IllegalStateException("no free reference after eight draws");
        }
        gifts.history(id, "reported", null, now);
        audit.changed("donation.reported", caller, caller.accountId(), DonationAudit.details().gift(publicId)
                .put("case", aidCase.code()).put("method", method.id()).put("details", details.publicId())
                .put("amount", report.amount()).put("account_linked", caller.signedIn()));
        idempotency.complete(Idempotency.REPORT_SCOPE, Idempotency.REPORT_OPERATION, idempotencyKey, 201, id);
        return receipt(gifts.trackedById(id).orElseThrow());
    }

    /** True when a report naming these details can no longer be accepted: they were replaced, or their method withdrawn, 24 hours ago or more. */
    private static boolean noLongerShown(Details details, Method method, Instant now) {
        if (details.supersededAt() != null && !now.isBefore(details.supersededAt().plus(DETAILS_GRACE))) {
            return true;
        }
        return !method.active() && (method.deactivatedAt() == null || !now.isBefore(method.deactivatedAt().plus(DETAILS_GRACE)));
    }

    /**
     * The receipt of a gift. Its two secrets are computed, never read: the same gift always
     * yields the same tracking code and the same upload token with the same expiry.
     */
    private Views.Receipt receipt(Tracked gift) {
        Views.EvidenceUpload upload = settings.evidenceRoot() == null ? null
                : new Views.EvidenceUpload(keys.evidenceToken(gift.reference(), gift.secret()),
                        Timestamps.format(gift.reportedAt().plus(EVIDENCE_WINDOW)), org.apvgakasinay.shared.ApiOperation.IMAGE_MAX_BYTES,
                        EVIDENCE_FORMATS);
        return new Views.Receipt(gift.reference(), keys.trackingCode(gift.reference(), gift.secret()), "reported",
                gift.reportedCaseCode(), gift.reportedAmount(), Timestamps.format(gift.reportedAt()), upload);
    }

    /**
     * The state of the one gift a tracking code belongs to. A wrong code, a malformed code and
     * an unknown reference are answered alike, after the same work: 404 donation_not_found.
     *
     * <p>Wrong codes are counted per reference, which is the first eight symbols of the code,
     * before the code is looked at; a code no reference can be read from is bounded by the
     * per-address limit alone.
     */
    @Transactional(readOnly = true)
    public Views.Status status(String typed) {
        String bare = typed == null || typed.length() > 64 ? "" : typed.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
        String code = Codes.normalize(bare, 20);
        String referenceSymbols = bare.length() >= 8 ? Codes.normalize(bare.substring(0, 8), 8) : null;
        String reference = referenceSymbols == null ? null : Codes.dashed(referenceSymbols);

        if (reference != null) {
            long wait = throttle.count(DonationThrottle.STATUS_WRONG_BY_REFERENCE, reference);
            if (wait > 0) {
                throw DonationThrottle.tooMany(wait);
            }
        }
        Tracked gift = code == null ? null : gifts.trackedByReference(reference).orElse(null);
        // The same work whether or not there is such a gift.
        String expected = keys.trackingTail(gift == null ? NO_SECRET : gift.secret());
        boolean right = DonationKeys.same(expected, code == null ? "" : code.substring(8)) && gift != null
                // A gift staff entered was never given a tracking code.
                && gift.origin().equals("reported");
        if (!right) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "donation_not_found", "Gift not found");
        }
        throttle.forgive(DonationThrottle.STATUS_WRONG_BY_REFERENCE, reference);

        // A gift that was reversed in full and entered again is followed to its corrected record (8.3).
        Tracked record = gift;
        for (int hops = 0; hops < 16; hops++) {
            Optional<Tracked> reentry = gifts.trackedReentryOf(record.id());
            if (reentry.isEmpty()) {
                break;
            }
            record = reentry.get();
        }
        boolean confirmed = record.state().equals("confirmed");
        return new Views.Status(gift.reference(), record.state(), gift.reportedCaseCode(), gift.reportedAmount(),
                Timestamps.format(gift.reportedAt()), Timestamps.format(record.decidedAt()),
                confirmed ? record.confirmedAmount() : null, confirmed ? record.confirmedCaseCode() : null,
                confirmed ? record.netAmount() : null, record.state().equals("rejected") ? record.rejectionReason() : null,
                gift.evidenceAttached());
    }
}
