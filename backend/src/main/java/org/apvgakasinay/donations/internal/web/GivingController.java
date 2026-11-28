package org.apvgakasinay.donations.internal.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.regex.Pattern;
import org.apvgakasinay.donations.internal.EvidenceService;
import org.apvgakasinay.donations.internal.GivingService;
import org.apvgakasinay.donations.internal.Views;
import org.apvgakasinay.shared.ApiCaller;
import org.apvgakasinay.shared.ApiException;
import org.apvgakasinay.shared.ApiOperation;
import org.apvgakasinay.shared.ApiOperation.Access;
import org.apvgakasinay.shared.ApiOperation.Body;
import org.apvgakasinay.shared.ErrorWriter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * The four giving operations (contract/donation-lifecycle.md, 11.1): open to every session
 * state, taking no gift identifier. A donor reaches a gift only through a secret bound to that
 * one gift: the upload token or the tracking code.
 *
 * <p>Steps 1 to 6 of the order of checks ran before any method here; each method does step 7,
 * the validation of what was sent, and hands step 8 to a service.
 */
@RestController
@RequestMapping("/api")
class GivingController {

    private static final Pattern EVIDENCE_TOKEN = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final Set<String> REPORT = Set.of("caseCode", "amount", "paymentDetailsId", "paidOn", "payerName", "paymentReference",
            "contact", "display");
    static final Set<String> DISPLAY = Set.of("mode", "publicName");
    static final Set<String> DISPLAY_MODES = Set.of("named", "anonymous", "hidden");

    private final GivingService giving;
    private final EvidenceService evidence;
    private final JsonMapper json;

    GivingController(GivingService giving, EvidenceService evidence, JsonMapper json) {
        this.giving = giving;
        this.evidence = evidence;
        this.json = json;
    }

    @GetMapping("/giving/methods")
    @ApiOperation(access = Access.ANY, limit = "giving.methods")
    Views.PaymentMethodList methods() {
        return giving.methods();
    }

    @PostMapping("/donations")
    @ApiOperation(access = Access.ANY, limit = "giving.report", body = Body.JSON)
    ResponseEntity<Views.Receipt> report(HttpServletRequest request) {
        String key = JsonRequest.idempotencyKey(request);
        JsonRequest body = JsonRequest.read(request, json, REPORT);
        String caseCode = body.matching("caseCode", JsonRequest.CASE_CODE, true);
        long amount = body.amount("amount");
        String detailsId = body.matching("paymentDetailsId", JsonRequest.DETAILS_ID, true);
        java.time.LocalDate paidOn = body.date("paidOn");
        String payerName = body.text("payerName", 2, 80, true);
        String paymentReference = body.text("paymentReference", 1, 64, false);
        String contact = body.text("contact", 3, 120, false);
        Display display = display(body);
        GivingService.Report report = new GivingService.Report(caseCode, amount, detailsId, paidOn, payerName, paymentReference, contact,
                display.mode(), display.publicName());
        return ResponseEntity.status(201).body(giving.report(report, key, body.bytes(), ApiCaller.of(request)));
    }

    /** How the donor appears: required, with no default on the server. A name is kept only for a named gift. */
    record Display(String mode, String publicName) {
    }

    static Display display(JsonRequest body) {
        JsonRequest display = body.object("display", DISPLAY, true);
        String mode = display.oneOf("mode", DISPLAY_MODES);
        String publicName = display.text("publicName", 2, 60, mode.equals("named"));
        return new Display(mode, mode.equals("named") ? publicName : null);
    }

    @PostMapping("/donations/evidence")
    @ApiOperation(access = Access.ANY, limit = "giving.evidence", body = Body.IMAGE)
    ResponseEntity<Void> attachEvidence(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String token = request.getHeader("X-Evidence-Token");
        if (token == null || token.isEmpty()) {
            throw ApiException.invalid("X-Evidence-Token", "required", "X-Evidence-Token is required");
        }
        if (!EVIDENCE_TOKEN.matcher(token).matches()) {
            throw ApiException.invalid("X-Evidence-Token", "invalid_format", "X-Evidence-Token has the wrong format");
        }
        // The body is handed over unread: it is read only once the token has been accepted.
        try {
            evidence.attach(token, request.getInputStream(), ApiCaller.of(request));
        } catch (ApiException refused) {
            if (refused.status() != HttpStatus.PAYLOAD_TOO_LARGE) {
                throw refused;
            }
            // More bytes arrived than a picture may have. The answer is sent now, before the server
            // gives up on the rest of the body: left in its buffer, it would never reach the client.
            ErrorWriter.write(request, response, 413, refused.code(), refused.getMessage());
            response.flushBuffer();
            return null;
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/donations/status")
    @ApiOperation(access = Access.ANY, limit = "giving.status", body = Body.JSON)
    Views.Status status(HttpServletRequest request) {
        JsonRequest body = JsonRequest.read(request, json, Set.of("trackingCode"));
        if (!body.has("trackingCode")) {
            throw ApiException.invalid("trackingCode", "required", "trackingCode is required");
        }
        // Anything that is text is looked up: a code that is malformed is answered like one that is wrong.
        return giving.status(body.rawText("trackingCode"));
    }
}
