package org.apvgakasinay.donations;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apvgakasinay.identity.Browser;
import org.junit.jupiter.api.Test;

/** Section 7 of contract/tests/donation-acceptance.md: following one gift with its tracking code. */
class TrackingIT extends DonationIntegration {

    private static String withoutRequestId(Browser.Answer answer) {
        return answer.body().replaceAll("\"requestId\":\"[0-9a-f]+\"", "");
    }

    /** A gift whose every private detail is a distinctive word, so its absence from an answer can be checked. */
    private Reported private_(String details) {
        Map<String, Object> report = reportOf("HELP-904", 5000, details);
        report.put("payerName", "Quagga Payer");
        report.put("paymentReference", "ZEBRA-REF-1");
        report.put("contact", "okapi@example.test");
        report.put("display", map("mode", "named", "publicName", "Tapir Public"));
        return reported(post(visitor(), "/api/donations", body(report), key()));
    }

    private static void assertNothingPersonal(Browser.Answer status) {
        assertThat(status.body()).doesNotContain("Quagga").doesNotContain("ZEBRA").doesNotContain("okapi").doesNotContain("Tapir")
                .doesNotContain("Sample Staff").doesNotContain("note").doesNotContain("acc_").doesNotContain("don_");
    }

    @Test
    void d7_1_d7_2_d7_3_theStatusShowsAnAmountACaseAndAStateAndNothingAboutAPerson() {
        String details = zelle();
        Reported gift = private_(details);
        Browser tracker = visitor();

        Browser.Answer reported = status(tracker, gift.trackingCode());
        assertThat(reported.status()).as(reported.body()).isEqualTo(200);
        assertValid("DonationStatus", reported);
        assertThat(reported.header("Cache-Control")).isEqualTo("no-store");
        JsonNode status = reported.json();
        assertThat(status.get("reference").asText()).isEqualTo(gift.reference());
        assertThat(status.get("state").asText()).isEqualTo("reported");
        assertThat(status.get("caseCode").asText()).isEqualTo("HELP-904");
        assertThat(status.get("reportedAmount").asLong()).isEqualTo(5000);
        assertThat(status.get("decidedAt").isNull()).isTrue();
        assertThat(status.get("confirmedAmount").isNull()).isTrue();
        assertThat(status.get("netAmount").isNull()).isTrue();
        assertThat(status.get("rejectionReason").isNull()).isTrue();
        assertThat(status.get("evidenceAttached").asBoolean()).isFalse();
        assertNothingPersonal(reported);

        Staff owner = owner();
        Map<String, Object> confirmation = confirmationOf("HELP-904", 4800);
        confirmation.put("note", "Elephant note: the bank kept a fee.");
        confirmation.put("providerReference", "GIRAFFE-PROVIDER-9");
        assertThat(post(owner.browser(), "/api/admin/donations/" + giftId(gift.reference()) + "/confirm", body(confirmation), key()).status()).isEqualTo(200);
        Browser.Answer confirmed = status(tracker, gift.trackingCode());
        assertValid("DonationStatus", confirmed);
        assertThat(confirmed.json().get("state").asText()).isEqualTo("confirmed");
        assertThat(confirmed.json().get("reportedAmount").asLong()).isEqualTo(5000);
        assertThat(confirmed.json().get("confirmedAmount").asLong()).isEqualTo(4800);
        assertThat(confirmed.json().get("confirmedCaseCode").asText()).isEqualTo("HELP-904");
        assertThat(confirmed.json().get("netAmount").asLong()).isEqualTo(4800);
        assertThat(confirmed.json().get("decidedAt").asText()).isNotBlank();
        assertNothingPersonal(confirmed);
        assertThat(confirmed.body()).doesNotContain("Elephant").doesNotContain("GIRAFFE");

        Reported other = private_(details);
        owner.browser().post("/api/admin/donations/" + giftId(other.reference()) + "/reject",
                body(map("reason", "duplicate", "note", "Elephant note: already recorded in another report.")));
        Browser.Answer rejected = status(tracker, other.trackingCode());
        assertValid("DonationStatus", rejected);
        assertThat(rejected.json().get("state").asText()).isEqualTo("rejected");
        assertThat(rejected.json().get("rejectionReason").asText()).isEqualTo("duplicate");
        assertThat(rejected.json().get("confirmedAmount").isNull()).isTrue();
        assertNothingPersonal(rejected);
        assertThat(rejected.body()).doesNotContain("Elephant");
    }

    @Test
    void d7_4_aWrongCodeAMalformedCodeAndAnUnknownReferenceAreAnsweredAlike() {
        Reported gift = report("HELP-904", 5000, zelle());
        String code = gift.trackingCode();
        // The right reference with another secret part; a reference no gift has; and things that are not codes at all.
        String wrongSecret = code.substring(0, 10) + (code.endsWith("0000-0000-0000") ? "1111-1111-1111" : "0000-0000-0000");
        String unknownReference = (code.startsWith("0000-0000") ? "1111-1111" : "0000-0000") + code.substring(9);
        List<String> wrong = List.of(wrongSecret, unknownReference, "not a tracking code", "7K4M-2QXD", "ILOU-ILOU-ILOU-ILOU-ILOU", "",
                code + "-0000", "x".repeat(500));

        Browser.Answer first = null;
        for (String typed : wrong) {
            Browser.Answer answer = status(visitor(), typed);
            assertError(answer, 404, "donation_not_found");
            first = first == null ? answer : first;
            assertThat(withoutRequestId(answer)).as(typed).isEqualTo(withoutRequestId(first));
        }
        // What is not text is not a code that was mistyped: it is a request that was not made as the contract says.
        assertFieldError(visitor().post("/api/donations/status", body(map("trackingCode", 12345))), "trackingCode", "invalid_type");
        assertFieldError(visitor().post("/api/donations/status", "{}"), "trackingCode", "required");
        assertFieldError(visitor().post("/api/donations/status", body(map("trackingCode", code, "reference", gift.reference()))), "reference", "unknown_field");

        // The code itself is found however it was typed: lower case, without its dashes.
        assertThat(status(visitor(), code.toLowerCase()).status()).isEqualTo(200);
        assertThat(status(visitor(), code.replace("-", "")).status()).isEqualTo(200);
    }

    @Test
    void d7_5_tenWrongCodesForOneReferenceCloseItForFifteenMinutesWhateverTheCode() {
        Reported gift = report("HELP-904", 5000, zelle());
        String code = gift.trackingCode();
        String wrong = code.substring(0, 10) + (code.endsWith("0000-0000-0000") ? "1111-1111-1111" : "0000-0000-0000");

        // Ten wrong codes, each from another address: no single address is over its own limit.
        for (int i = 0; i < 10; i++) {
            assertError(status(visitor(), wrong), 404, "donation_not_found");
        }
        Browser.Answer closed = status(visitor(), wrong);
        assertError(closed, 429, "rate_limited");
        assertThat(Integer.parseInt(closed.header("Retry-After"))).isBetween(1, 900);
        // The right code too: for that reference, whatever the code.
        assertError(status(visitor(), code), 429, "rate_limited");
        // Another gift's holder is not affected.
        Reported other = report("HELP-904", 100, currentDetails("zelle"));
        assertThat(status(visitor(), other.trackingCode()).status()).isEqualTo(200);

        advance(Duration.ofMinutes(15));
        assertThat(status(visitor(), code).status()).isEqualTo(200);
        // Right codes are not counted: the holder can look as often as the per-address limit allows.
        Browser holder = visitor();
        for (int i = 0; i < 9; i++) {
            assertThat(status(holder, code).status()).isEqualTo(200);
        }
        assertThat(count("select coalesce(sum(attempts), 0) from donation_throttle where scope = 'status.reference'")).isZero();
    }

    @Test
    void d9_1_statusLookupsAreLimitedPerAddress() {
        Reported gift = report("HELP-904", 5000, zelle());
        Browser holder = visitor();
        for (int i = 0; i < 10; i++) {
            assertThat(status(holder, gift.trackingCode()).status()).isEqualTo(200);
        }
        Browser.Answer limited = status(holder, gift.trackingCode());
        assertError(limited, 429, "rate_limited");
        assertThat(Integer.parseInt(limited.header("Retry-After"))).isBetween(1, 900);
        // An unreadable code is bounded by this limit alone: the same answer, counted against the address.
        Browser guesser = visitor();
        for (int i = 0; i < 10; i++) {
            assertError(status(guesser, "!!!"), 404, "donation_not_found");
        }
        assertError(status(guesser, "!!!"), 429, "rate_limited");
        advance(Duration.ofMinutes(15));
        assertThat(status(holder, gift.trackingCode()).status()).isEqualTo(200);
    }

    @Test
    void d7_6_d7_8_theTrackingCodeIsShownOnceAndNeverInAnAddress() {
        Reported gift = report("HELP-904", 5000, zelle());
        String secretPart = gift.trackingCode().substring(10);
        Staff owner = owner();
        String id = giftId(gift.reference());
        JsonNode detail = confirm(owner, gift, "HELP-904", 5000);

        // No staff answer reveals it: the organisers cannot read it either.
        for (Browser.Answer answer : List.of(owner.browser().get("/api/admin/donations/" + id),
                owner.browser().get("/api/admin/donations?status=all"))) {
            assertThat(answer.body()).doesNotContain(secretPart).doesNotContain(secretPart.replace("-", "")).doesNotContain("trackingCode");
        }
        assertThat(detail.toString()).doesNotContain(secretPart);
        assertThat(status(visitor(), gift.trackingCode()).body()).doesNotContain(secretPart);
        // It is nowhere in the database either: it is computed from a value stored with the gift and a key that is not.
        for (String table : List.of("donation", "donation_history", "audit_event", "idempotency_record", "donation_throttle")) {
            assertThat(count("select count(*) from " + table + " t where position('" + secretPart.replace("-", "") + "' in row(t.*)::text) > 0")).as(table).isZero();
        }

        // A POST, so that the code is never part of an address; there is no route that takes it in one.
        Browser browser = visitor();
        Browser.Answer get = browser.get("/api/donations/status?trackingCode=" + gift.trackingCode());
        assertError(get, 405, "method_not_allowed");
        assertThat(get.header("Allow")).isEqualTo("POST");
        assertError(browser.get("/api/donations/" + gift.trackingCode()), 404, "not_found");
        assertError(browser.get("/api/donations/status/" + gift.trackingCode()), 404, "not_found");
    }
}
