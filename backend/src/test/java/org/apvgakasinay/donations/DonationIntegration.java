package org.apvgakasinay.donations;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apvgakasinay.donations.internal.PaymentMethodAdministration;
import org.apvgakasinay.donations.internal.PaymentMethodAdministration.Definition;
import org.apvgakasinay.donations.internal.PaymentMethodRepository.Localized;
import org.apvgakasinay.donations.internal.PaymentMethodRepository.RecipientField;
import org.apvgakasinay.fixtures.FixtureImporter;
import org.apvgakasinay.identity.Browser;
import org.apvgakasinay.identity.IdentityIntegration;
import org.apvgakasinay.support.Json;
import org.apvgakasinay.support.Repo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Base of the donation tests: the whole application over real HTTP against real PostgreSQL,
 * with a cookie jar per simulated browser, as contract/tests/donation-acceptance.md asks.
 *
 * <p>Test names start with the identifier of the scenario they cover in that list, for example
 * {@code d4_7_...} for scenario 4.7. Each test starts from contract/fixtures/example.json (the
 * general fund, HELP-901 and HELP-904 active, HELP-902 a draft, HELP-903 delivered), with no
 * payment method and no gift, at the instant the identity tests start from.
 */
public abstract class DonationIntegration extends IdentityIntegration {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger KEYS = new AtomicInteger();

    protected static final String TODAY = "2026-11-24";

    @Autowired
    protected FixtureImporter importer;

    @Autowired
    protected PaymentMethodAdministration paymentMethods;

    /** A reported gift, as its receipt described it. */
    public record Reported(String reference, String trackingCode, String uploadToken, JsonNode receipt) {
    }

    @BeforeEach
    void startFromTheExampleWithNoMethodAndNoGift() {
        clearDonationData();
        importer.replaceAllData(Json.readMap(Repo.contract("fixtures/example.json")));
    }

    @AfterEach
    void leaveNoDonationDataBehind() {
        clearDonationData();
    }

    private void clearDonationData() {
        jdbc.sql("""
                truncate table donation_correction, donation_history, donation_evidence, donation, payment_method_details, payment_method,
                               idempotency_record, donation_throttle
                restart identity cascade
                """).update();
    }

    // ------------------------------------------------------------------ payment methods

    protected static Definition sampleDetails(String sendTo) {
        return new Definition(new Localized("Send your gift, then come back to report it.", "Envoyez votre don, puis revenez le déclarer."),
                List.of(new RecipientField(new Localized("Send to", "Envoyer à"), sendTo, "text")));
    }

    /** Creates an active method with one details version and returns the identifier of that version. */
    protected String method(String id, int position) {
        paymentMethods.create(id, id.substring(0, 1).toUpperCase() + id.substring(1), id.substring(0, 1).toUpperCase() + id.substring(1), position, "test-operator");
        paymentMethods.publishDetails(id, sampleDetails("gifts+" + id + "@example.test"), "test-operator");
        paymentMethods.activate(id, null, "test-operator");
        return currentDetails(id);
    }

    protected String zelle() {
        return method("zelle", 1);
    }

    protected String currentDetails(String methodId) {
        return jdbc.sql("select public_id from payment_method_details where method_id = :id and superseded_at is null")
                .param("id", methodId).query(String.class).single();
    }

    // ------------------------------------------------------------------ requests

    protected static String key() {
        return "test-key-" + String.format("%020d", KEYS.incrementAndGet());
    }

    protected static String body(Map<String, Object> values) {
        try {
            return JSON.writeValueAsString(values);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    /** A map in the order given: name, value, name, value. */
    protected static Map<String, Object> map(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put((String) pairs[i], pairs[i + 1]);
        }
        return values;
    }

    /** POST of a JSON body with an Idempotency-Key header (none when the key is null). */
    protected static Browser.Answer post(Browser browser, String path, String json, String idempotencyKey) {
        if (idempotencyKey != null) {
            browser.extraHeaders.put("Idempotency-Key", idempotencyKey);
        }
        try {
            return browser.post(path, json);
        } finally {
            browser.extraHeaders.remove("Idempotency-Key");
        }
    }

    protected static Map<String, Object> reportOf(String caseCode, long amount, String detailsId) {
        return map("caseCode", caseCode, "amount", amount, "paymentDetailsId", detailsId, "paidOn", TODAY,
                "payerName", "Sample Donor", "display", map("mode", "anonymous"));
    }

    /** A guest in a new browser reports a gift, and the receipt is returned. */
    protected Reported report(String caseCode, long amount, String detailsId) {
        Browser guest = browser();
        guest.csrf();
        return reported(post(guest, "/api/donations", body(reportOf(caseCode, amount, detailsId)), key()));
    }

    protected Reported reported(Browser.Answer answer) {
        assertThat(answer.status()).as(answer.body()).isEqualTo(201);
        assertValid("DonationReceipt", answer);
        JsonNode receipt = answer.json();
        return new Reported(receipt.get("reference").asText(), receipt.get("trackingCode").asText(),
                receipt.at("/evidenceUpload/token").asText(null), receipt);
    }

    protected String giftId(String reference) {
        return jdbc.sql("select public_id from donation where reference = :reference").param("reference", reference).query(String.class).single();
    }

    protected Staff owner() {
        return staff("sample.owner@example.org", "super_admin");
    }

    /** A new browser of a staff member who signed in again with both factors, for a test that moved the clock past a session's life. */
    protected Browser signedInAgain(Staff staff) {
        Browser browser = browser();
        Browser.Answer login = browser.login(staff.email(), PASSWORD);
        assertThat(login.json().get("status").asText()).as(login.body()).isEqualTo("mfa_required");
        nextCode();
        Browser.Answer verified = browser.post("/api/auth/mfa/verify", json("method", "totp", "code", code(staff.secret())));
        assertThat(verified.status()).as(verified.body()).isEqualTo(200);
        browser.csrf();
        return browser;
    }

    /** A new browser that holds a session and its CSRF token, as a visitor's page does before any POST. */
    protected Browser visitor() {
        Browser browser = browser();
        browser.csrf();
        return browser;
    }

    protected static void assertFieldError(Browser.Answer answer, String field, String code) {
        assertThat(answer.status()).as(answer.body()).isEqualTo(400);
        assertThat(answer.code()).as(answer.body()).isEqualTo("validation_error");
        assertThat(answer.json().at("/error/fields/0/name").asText()).as(answer.body()).isEqualTo(field);
        assertThat(answer.fieldCode()).as(answer.body()).isEqualTo(code);
    }

    protected static Map<String, Object> confirmationOf(String caseCode, long amount) {
        return map("caseCode", caseCode, "confirmedAmount", amount, "receivedOn", TODAY, "listing", "as_requested");
    }

    /** The owner confirms a reported gift for a case and an amount, and the gift's detail is returned. */
    protected JsonNode confirm(Staff owner, Reported gift, String caseCode, long amount) {
        Browser.Answer answer = post(owner.browser(), "/api/admin/donations/" + giftId(gift.reference()) + "/confirm",
                body(confirmationOf(caseCode, amount)), key());
        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        assertValid("DonationDetail", answer);
        return answer.json();
    }

    protected Browser.Answer status(Browser browser, String trackingCode) {
        return browser.post("/api/donations/status", body(map("trackingCode", trackingCode)));
    }

    protected JsonNode publicCase(String code) {
        Browser.Answer answer = browser().get("/api/cases/" + code);
        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        return answer.json();
    }

    // ------------------------------------------------------------------ applied to everything every test did

    private static final Map<String, String> SUCCESS_SCHEMAS = Map.of(
            "GET /api/giving/methods", "PaymentMethodList",
            "POST /api/donations", "DonationReceipt",
            "POST /api/donations/status", "DonationStatus",
            "GET /api/admin/donations", "DonationPage");

    /**
     * Scenario 8.9, applied to every answer of every test: each giving and review answer, at
     * every status, is never stored and carries no ETag; a 405 has Allow; a 429 and a 503 have
     * Retry-After; and its body is valid against the closed schema the contract gives it.
     */
    @AfterEach
    void everyDonationAnswerMatchesTheContract() {
        for (Browser.Answer answer : List.copyOf(Browser.RECORDED)) {
            String path = answer.path().replaceAll("\\?.*", "");
            boolean donations = path.equals("/api/donations") || path.startsWith("/api/donations/") || path.startsWith("/api/giving/")
                    || path.startsWith("/api/admin/");
            if (!donations) {
                continue;
            }
            String what = answer.method() + " " + path + " -> " + answer.status();
            assertThat(answer.header("Cache-Control")).as(what).isEqualTo("no-store");
            assertThat(answer.header("ETag")).as(what).isNull();
            if (answer.status() == 405) {
                assertThat(answer.header("Allow")).as(what).isNotBlank();
            }
            if (answer.status() == 429 || answer.status() == 503) {
                assertThat(Integer.parseInt(answer.header("Retry-After"))).as(what).isBetween(1, 3600);
            }
            if (answer.method().equals("HEAD")) {
                continue;
            }
            if (answer.status() >= 400) {
                assertThat(contract.violations("Error", answer.body())).as(what + " " + answer.body()).isEmpty();
            } else if (answer.status() == 204) {
                assertThat(answer.body()).as(what).isEmpty();
            } else if (path.endsWith("/evidence") && answer.method().equals("GET")) {
                assertThat(answer.header("Content-Type")).as(what).startsWith("image/");
            } else {
                String schema = SUCCESS_SCHEMAS.getOrDefault(answer.method() + " " + path, "DonationDetail");
                assertThat(contract.violations(schema, answer.body())).as(what + " " + answer.body()).isEmpty();
            }
        }
    }
}
