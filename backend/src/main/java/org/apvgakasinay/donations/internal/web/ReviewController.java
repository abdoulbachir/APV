package org.apvgakasinay.donations.internal.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.apvgakasinay.donations.internal.ReviewService;
import org.apvgakasinay.donations.internal.Views;
import org.apvgakasinay.shared.ApiCaller;
import org.apvgakasinay.shared.ApiException;
import org.apvgakasinay.shared.ApiOperation;
import org.apvgakasinay.shared.ApiOperation.Access;
import org.apvgakasinay.shared.ApiOperation.Body;
import org.apvgakasinay.shared.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * The eleven donation review operations (contract/donation-lifecycle.md, sections 6 to 8). Every
 * one needs a fully signed-in session holding {@code donations.confirm}; both were checked at
 * step 6, before anything here ran, so a caller without the permission never learns whether a
 * gift exists.
 *
 * <p>Each method does step 7: the gift identifier, the query, the headers of the operation and
 * the body. A malformed gift identifier is a known path with a bad parameter: 400, not 404.
 */
@RestController
@RequestMapping("/api/admin/donations")
class ReviewController {

    private static final String PERMISSION = "donations.confirm";
    private static final Pattern REFERENCE = Pattern.compile("^[0-9A-HJKMNP-TV-Z]{4}-?[0-9A-HJKMNP-TV-Z]{4}$");
    private static final Set<String> STATES = Set.of("reported", "confirmed", "rejected", "all");
    private static final Set<String> CONFIRM = Set.of("caseCode", "confirmedAmount", "receivedOn", "providerReference", "listing", "note");
    private static final Set<String> ENTRY = Set.of("caseCode", "amount", "receivedOn", "providerReference", "method", "payerName", "display",
            "note", "reentryOf");

    private final ReviewService review;
    private final JsonMapper json;

    ReviewController(ReviewService review, JsonMapper json) {
        this.review = review;
        this.json = json;
    }

    @GetMapping
    @ApiOperation(access = Access.SIGNED_IN, permission = PERMISSION)
    Views.DonationPage list(@RequestParam Map<String, String> query, HttpServletRequest request) {
        String status = query.getOrDefault("status", "reported");
        if (!STATES.contains(status)) {
            throw ApiException.invalid("status", "invalid_value", "status is not an accepted value");
        }
        String caseCode = query.get("caseCode");
        if (caseCode != null) {
            if (caseCode.length() > 16 || !JsonRequest.CASE_CODE.matcher(caseCode).matches()) {
                throw ApiException.invalid("caseCode", "invalid_format", "caseCode has the wrong format");
            }
            caseCode = caseCode.toUpperCase(Locale.ROOT);
        }
        String reference = query.get("reference");
        if (reference != null) {
            if (!REFERENCE.matcher(reference).matches()) {
                throw ApiException.invalid("reference", "invalid_format", "reference has the wrong format");
            }
            String bare = reference.replace("-", "");
            reference = bare.substring(0, 4) + "-" + bare.substring(4);
        }
        return review.list(status, caseCode, reference, PageRequest.from(query), ApiCaller.of(request));
    }

    @PostMapping
    @ApiOperation(access = Access.SIGNED_IN, permission = PERMISSION, body = Body.JSON)
    ResponseEntity<Views.Detail> enter(HttpServletRequest request) {
        String key = JsonRequest.idempotencyKey(request);
        JsonRequest body = JsonRequest.read(request, json, ENTRY);
        String caseCode = body.matching("caseCode", JsonRequest.CASE_CODE, true);
        long amount = body.amount("amount");
        java.time.LocalDate receivedOn = body.date("receivedOn");
        String providerReference = body.text("providerReference", 1, 64, false);
        // Exactly one of a method record or a free description of a way that is not a record.
        JsonRequest method = body.object("method", Set.of("id", "description"), true);
        String methodId = method.matching("id", JsonRequest.METHOD_ID, false);
        String methodDescription = method.text("description", 2, 80, false);
        if ((methodId == null) == (methodDescription == null)) {
            throw ApiException.invalid("method", "invalid_value", "method takes exactly one of id and description");
        }
        String payerName = body.text("payerName", 2, 80, true);
        GivingController.Display display = GivingController.display(body);
        String note = body.text("note", 1, 500, true, true);
        String reentryOf = body.matching("reentryOf", JsonRequest.DONATION_ID, false);
        ReviewService.Entry entry = new ReviewService.Entry(caseCode, amount, receivedOn, providerReference, methodId, methodDescription,
                payerName, display.mode(), display.publicName(), note, reentryOf);
        return ResponseEntity.status(201).body(review.enter(entry, key, body.bytes(), ApiCaller.of(request)));
    }

    @GetMapping("/{donationId}")
    @ApiOperation(access = Access.SIGNED_IN, permission = PERMISSION)
    Views.Detail detail(@PathVariable String donationId, HttpServletRequest request) {
        return review.detail(id(donationId), ApiCaller.of(request));
    }

    /** The stored screenshot, with the headers that keep a browser from treating it as anything but a picture (4.4). */
    @GetMapping("/{donationId}/evidence")
    @ApiOperation(access = Access.SIGNED_IN, permission = PERMISSION)
    ResponseEntity<byte[]> evidence(@PathVariable String donationId, HttpServletRequest request) {
        ReviewService.Screenshot screenshot = review.evidence(id(donationId), ApiCaller.of(request));
        return ResponseEntity.ok()
                .contentType(screenshot.format().equals("jpeg") ? MediaType.IMAGE_JPEG : MediaType.IMAGE_PNG)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + screenshot.fileName() + "\"")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .header("X-Content-Type-Options", "nosniff")
                .header("Cross-Origin-Resource-Policy", "same-origin")
                .body(screenshot.bytes());
    }

    @PostMapping("/{donationId}/evidence/delete")
    @ApiOperation(access = Access.SIGNED_IN, permission = PERMISSION)
    Views.Detail deleteEvidence(@PathVariable String donationId, HttpServletRequest request) {
        String id = id(donationId);
        JsonRequest.requireEmpty(request);
        return review.deleteEvidence(id, ApiCaller.of(request));
    }

    @PostMapping("/{donationId}/confirm")
    @ApiOperation(access = Access.SIGNED_IN, permission = PERMISSION, body = Body.JSON)
    Views.Detail confirm(@PathVariable String donationId, HttpServletRequest request) {
        String id = id(donationId);
        String key = JsonRequest.idempotencyKey(request);
        JsonRequest body = JsonRequest.read(request, json, CONFIRM);
        // The case and the amount are always stated: nothing is taken from the report (6.3).
        ReviewService.Confirm confirm = new ReviewService.Confirm(
                body.matching("caseCode", JsonRequest.CASE_CODE, true),
                body.amount("confirmedAmount"),
                body.date("receivedOn"),
                body.text("providerReference", 1, 64, false),
                body.oneOf("listing", Set.of("as_requested", "anonymous", "hidden")),
                body.text("note", 1, 500, false, true));
        return review.confirm(id, confirm, key, body.bytes(), ApiCaller.of(request));
    }

    @PostMapping("/{donationId}/reject")
    @ApiOperation(access = Access.SIGNED_IN, permission = PERMISSION, body = Body.JSON)
    Views.Detail reject(@PathVariable String donationId, HttpServletRequest request) {
        String id = id(donationId);
        JsonRequest body = JsonRequest.read(request, json, Set.of("reason", "note"));
        String reason = body.oneOf("reason", Set.of("not_received", "duplicate", "other"));
        String note = body.text("note", 1, 500, reason.equals("other"), true);
        return review.reject(id, new ReviewService.Reject(reason, note), ApiCaller.of(request));
    }

    @PostMapping("/{donationId}/reopen")
    @ApiOperation(access = Access.SIGNED_IN, permission = PERMISSION)
    Views.Detail reopen(@PathVariable String donationId, HttpServletRequest request) {
        String id = id(donationId);
        JsonRequest.requireEmpty(request);
        return review.reopen(id, ApiCaller.of(request));
    }

    /** A record that was wrong: no money moved. Its required fields are not those of a refund (8.1). */
    @PostMapping("/{donationId}/reversals")
    @ApiOperation(access = Access.SIGNED_IN, permission = PERMISSION, body = Body.JSON)
    Views.Detail reverse(@PathVariable String donationId, HttpServletRequest request) {
        String id = id(donationId);
        String key = JsonRequest.idempotencyKey(request);
        JsonRequest body = JsonRequest.read(request, json, Set.of("amount", "reason", "note"));
        ReviewService.Reversal reversal = new ReviewService.Reversal(
                body.amount("amount"),
                body.oneOf("reason", Set.of("duplicate_confirmation", "wrong_amount", "wrong_case", "not_received", "other")),
                body.text("note", 1, 500, true, true));
        return review.reverse(id, reversal, key, body.bytes(), ApiCaller.of(request));
    }

    /** Money that really went back to the donor. Its required fields are not those of a reversal (8.1). */
    @PostMapping("/{donationId}/refunds")
    @ApiOperation(access = Access.SIGNED_IN, permission = PERMISSION, body = Body.JSON)
    Views.Detail refund(@PathVariable String donationId, HttpServletRequest request) {
        String id = id(donationId);
        String key = JsonRequest.idempotencyKey(request);
        JsonRequest body = JsonRequest.read(request, json, Set.of("amount", "refundedOn", "providerReference", "note"));
        ReviewService.Refund refund = new ReviewService.Refund(
                body.amount("amount"),
                body.date("refundedOn"),
                body.text("providerReference", 1, 64, false),
                body.text("note", 1, 500, true, true));
        return review.refund(id, refund, key, body.bytes(), ApiCaller.of(request));
    }

    @PostMapping("/{donationId}/listing")
    @ApiOperation(access = Access.SIGNED_IN, permission = PERMISSION, body = Body.JSON)
    Views.Detail restrictListing(@PathVariable String donationId, HttpServletRequest request) {
        String id = id(donationId);
        JsonRequest body = JsonRequest.read(request, json, Set.of("listing"));
        return review.restrictListing(id, body.oneOf("listing", Set.of("anonymous", "hidden")), ApiCaller.of(request));
    }

    private static String id(String donationId) {
        if (!JsonRequest.DONATION_ID.matcher(donationId).matches()) {
            throw ApiException.invalid("donationId", "invalid_format", "donationId has the wrong format");
        }
        return donationId;
    }
}
