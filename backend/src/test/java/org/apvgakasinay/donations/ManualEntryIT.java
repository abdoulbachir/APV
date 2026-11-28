package org.apvgakasinay.donations;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.apvgakasinay.identity.Browser;
import org.junit.jupiter.api.Test;

/** Section 5 of contract/tests/donation-acceptance.md: a gift staff enter because it arrived without a report. */
class ManualEntryIT extends DonationIntegration {

    private static final String ENTRIES = "/api/admin/donations";

    private static Map<String, Object> cash(String caseCode, long amount) {
        return map("caseCode", caseCode, "amount", amount, "receivedOn", TODAY, "method", map("description", "Cash, in person"),
                "payerName", "Sample Donor", "display", map("mode", "anonymous"), "note", "Handed over at the family gathering.");
    }

    private Browser.Answer enter(Staff owner, Map<String, Object> entry) {
        return post(owner.browser(), ENTRIES, body(entry), key());
    }

    private JsonNode entered(Browser.Answer answer) {
        assertThat(answer.status()).as(answer.body()).isEqualTo(201);
        assertValid("DonationDetail", answer);
        return answer.json();
    }

    private Browser.Answer reverse(Staff owner, String id, long amount, String reason) {
        return post(owner.browser(), ENTRIES + "/" + id + "/reversals", body(map("amount", amount, "reason", reason, "note", "Entered wrongly.")), key());
    }

    @Test
    void d5_1_aCashGiftIsConfirmedAtOnceWithOneEntryAndNothingReported() {
        Staff owner = owner();
        long fund = publicCase("FUND").at("/financials/externalDonations").asLong();

        JsonNode detail = entered(enter(owner, cash("FUND", 20_000)));
        assertThat(detail.get("origin").asText()).isEqualTo("staff_entry");
        assertThat(detail.get("state").asText()).isEqualTo("confirmed");
        assertThat(detail.get("reported").isNull()).isTrue();
        assertThat(detail.get("shownDetails").isNull()).isTrue();
        assertThat(detail.at("/method/id").isNull()).isTrue();
        assertThat(detail.at("/method/description").asText()).isEqualTo("Cash, in person");
        assertThat(detail.get("evidence").asText()).isEqualTo("none");
        assertThat(detail.at("/decision/caseCode").asText()).isEqualTo("FUND");
        assertThat(detail.at("/decision/amount").asLong()).isEqualTo(20_000);
        assertThat(detail.at("/decision/note").asText()).startsWith("Handed over");
        assertThat(detail.get("netAmount").asLong()).isEqualTo(20_000);
        assertThat(detail.get("history")).hasSize(1);
        assertThat(detail.at("/history/0/action").asText()).isEqualTo("entered");
        // No tracking code: nobody reported this gift, and no answer about it carries one.
        assertThat(detail.toString()).doesNotContain("trackingCode").doesNotContain("evidenceUpload");

        assertThat(publicCase("FUND").at("/financials/externalDonations").asLong()).isEqualTo(fund + 20_000);
        assertThat(publicCase("FUND").at("/ledger/0/kind").asText()).isEqualTo("donation");
        assertThat(count("select count(*) from donation where origin = 'staff_entry' and reported_amount_cents is null and reported_at is null")).isEqualTo(1);
        assertThat(count("select count(*) from audit_event where event_type = 'donation.entered' and details ->> 'method' = 'described'")).isEqualTo(1);
        // It is in the review list with the gifts that were reported.
        JsonNode listed = owner.browser().get(ENTRIES + "?status=confirmed").json();
        assertThat(listed.at("/items/0/origin").asText()).isEqualTo("staff_entry");
        assertThat(listed.at("/items/0/reportedAmount").isNull()).isTrue();
        assertThat(listed.at("/items/0/caseCode").asText()).isEqualTo("FUND");
    }

    @Test
    void d5_2_d5_3_theMethodIsExactlyOneOfARecordOrADescriptionAndTheReasonIsRequired() {
        zelle();
        paymentMethods.deactivate("zelle", "test-operator");
        Staff owner = owner();

        Map<String, Object> both = cash("FUND", 1000);
        both.put("method", map("id", "zelle", "description", "Cash, in person"));
        assertFieldError(enter(owner, both), "method", "invalid_value");
        Map<String, Object> neither = cash("FUND", 1000);
        neither.put("method", map());
        assertFieldError(enter(owner, neither), "method", "invalid_value");
        Map<String, Object> unknown = cash("FUND", 1000);
        unknown.put("method", map("id", "venmo"));
        assertFieldError(enter(owner, unknown), "method", "invalid_value");
        Map<String, Object> missing = cash("FUND", 1000);
        missing.remove("method");
        assertFieldError(enter(owner, missing), "method", "required");

        Map<String, Object> noNote = cash("FUND", 1000);
        noNote.remove("note");
        assertFieldError(enter(owner, noNote), "note", "required");
        Map<String, Object> draft = cash("HELP-902", 1000);
        assertFieldError(enter(owner, draft), "caseCode", "invalid_value");
        Map<String, Object> named = cash("FUND", 1000);
        named.put("display", map("mode", "named"));
        assertFieldError(enter(owner, named), "display.publicName", "required");
        assertFieldError(post(owner.browser(), ENTRIES, body(cash("FUND", 1000)), null), "Idempotency-Key", "required");
        assertThat(count("select count(*) from donation")).isZero();

        // A method record, active or not: the money arrived by it, whatever donors are offered today.
        Map<String, Object> byRecord = cash("FUND", 1000);
        byRecord.put("method", map("id", "zelle"));
        byRecord.put("providerReference", "ZL-555");
        JsonNode detail = entered(enter(owner, byRecord));
        assertThat(detail.at("/method/id").asText()).isEqualTo("zelle");
        assertThat(detail.at("/method/description").isNull()).isTrue();
        // One payment is never counted twice, entered or reported.
        assertError(enter(owner, byRecord), 409, "duplicate_payment_reference");
    }

    @Test
    void d5_1_theSameEntrySentTwiceWithOneKeyIsOneGift() {
        Staff owner = owner();
        String key = key();
        String sent = body(cash("FUND", 20_000));
        Browser.Answer first = post(owner.browser(), ENTRIES, sent, key);
        Browser.Answer again = post(owner.browser(), ENTRIES, sent, key);
        entered(first);
        assertThat(again.status()).isEqualTo(201);
        assertThat(again.body()).isEqualTo(first.body());
        assertThat(count("select count(*) from donation")).isEqualTo(1);
        assertThat(count("select count(*) from ledger_entry where kind = 'donation' and public_id like 'led_%'")).isEqualTo(1);
        assertError(post(owner.browser(), ENTRIES, body(cash("FUND", 20_001)), key), 409, "idempotency_key_reused");
    }

    @Test
    void d5_4_d6_10_aGiftReversedInFullIsEnteredAgainAndTheDonorsStatusFollowsIt() {
        // Reported for HELP-904 and wrongly confirmed there for the whole 50.00: the payment was for the general fund.
        Browser guest = visitor();
        Map<String, Object> report = reportOf("HELP-904", 5000, zelle());
        report.put("display", map("mode", "named", "publicName", "Sample D."));
        Reported gift = reported(post(guest, "/api/donations", body(report), key()));
        Staff owner = owner();
        String original = confirm(owner, gift, "HELP-904", 5000).get("id").asText();
        long platform = browser().get("/api/stats").json().get("received").asLong();

        assertThat(reverse(owner, original, 5000, "wrong_case").status()).isEqualTo(200);
        Map<String, Object> entry = map("caseCode", "FUND", "amount", 5000, "receivedOn", TODAY, "method", map("id", "zelle"),
                "payerName", "Sample Donor", "display", map("mode", "named", "publicName", "Another Name"),
                "note", "Confirmed for the wrong case; this replaces it.", "reentryOf", original);
        JsonNode reentry = entered(enter(owner, entry));
        assertThat(reentry.get("reentryOf").asText()).isEqualTo(original);
        assertThat(reentry.at("/decision/caseCode").asText()).isEqualTo("FUND");
        // The donor's own choice is taken over: the name they agreed to show, never one typed for them.
        assertThat(reentry.at("/display/mode").asText()).isEqualTo("named");
        assertThat(reentry.at("/display/publicName").asText()).isEqualTo("Sample D.");

        JsonNode before = owner.browser().get(ENTRIES + "/" + original).json();
        assertThat(before.get("reenteredAs").asText()).isEqualTo(reentry.get("id").asText());
        assertThat(before.get("netAmount").asLong()).isZero();
        assertThat(before.get("flags").toString()).contains("reentered");
        // The pair is one payment, not a possible duplicate of itself.
        assertThat(before.get("possibleDuplicates")).isEmpty();

        // One payment, counted once before and after the repair.
        assertThat(browser().get("/api/stats").json().get("received").asLong()).isEqualTo(platform);
        assertThat(publicCase("HELP-904").at("/case/received").asLong()).isZero();
        assertThat(publicCase("HELP-904").at("/counts/donors").asInt()).isZero();
        assertThat(publicCase("FUND").at("/donors/0/name").asText()).isEqualTo("Sample D.");

        // The tracking code the donor kept now shows the corrected record.
        JsonNode status = status(guest, gift.trackingCode()).json();
        assertThat(status.get("reference").asText()).isEqualTo(gift.reference());
        assertThat(status.get("state").asText()).isEqualTo("confirmed");
        assertThat(status.get("caseCode").asText()).isEqualTo("HELP-904");
        assertThat(status.get("confirmedCaseCode").asText()).isEqualTo("FUND");
        assertThat(status.get("confirmedAmount").asLong()).isEqualTo(5000);
        assertThat(status.get("netAmount").asLong()).isEqualTo(5000);

        // Re-entered already: it cannot be re-entered a second time.
        assertError(enter(owner, entry), 409, "reentry_not_allowed");
    }

    @Test
    void d5_5_onlyAGiftThatNoLongerStandsAndWasNeverRefundedCanBeReentered() {
        String details = zelle();
        Staff owner = owner();
        String standing = confirm(owner, report("HELP-904", 5000, details), "HELP-904", 5000).get("id").asText();
        String partly = confirm(owner, report("HELP-904", 5000, details), "HELP-904", 5000).get("id").asText();
        reverse(owner, partly, 4999, "wrong_amount");
        String refunded = confirm(owner, report("HELP-904", 5000, details), "HELP-904", 5000).get("id").asText();
        assertThat(post(owner.browser(), ENTRIES + "/" + refunded + "/refunds",
                body(map("amount", 5000, "refundedOn", TODAY, "note", "The donor asked for it back.")), key()).status()).isEqualTo(200);
        String reported = giftId(report("HELP-904", 5000, details).reference());
        long entries = count("select count(*) from ledger_entry");

        for (String refusedId : List.of(standing, partly, refunded, reported, "don_AAAAAAAAAAAAAAAAAAAAAA")) {
            Map<String, Object> entry = cash("FUND", 5000);
            entry.put("reentryOf", refusedId);
            assertError(enter(owner, entry), 409, "reentry_not_allowed");
        }
        Map<String, Object> malformed = cash("FUND", 5000);
        malformed.put("reentryOf", "not-an-id");
        assertFieldError(enter(owner, malformed), "reentryOf", "invalid_format");
        assertThat(count("select count(*) from ledger_entry")).isEqualTo(entries);
        assertThat(count("select count(*) from donation where origin = 'staff_entry'")).isZero();
        assertThat(count("select count(*) from audit_event where event_type = 'donation.decision_refused' and details ->> 'rule' = 'reentry_not_allowed'")).isEqualTo(5);
    }

    @Test
    void d5_6_anAccountWithoutThePermissionIsRefusedBeforeTheGiftItNamesIsLookedAt() {
        Staff owner = owner();
        String existing = entered(enter(owner, cash("FUND", 1000))).get("id").asText();
        Staff admin = staff("sample.admin@example.org", "admin");
        Browser donor = donor("sample.donor@example.org");

        for (Browser browser : List.of(admin.browser(), donor)) {
            Map<String, Object> entry = cash("FUND", 5000);
            entry.put("reentryOf", existing);
            Browser.Answer real = post(browser, ENTRIES, body(entry), key());
            entry.put("reentryOf", "don_AAAAAAAAAAAAAAAAAAAAAA");
            Browser.Answer unknown = post(browser, ENTRIES, body(entry), key());
            entry.put("reentryOf", "not-an-id");
            Browser.Answer malformed = post(browser, ENTRIES, body(entry), key());
            for (Browser.Answer answer : List.of(real, unknown, malformed)) {
                assertError(answer, 403, "forbidden");
                assertThat(answer.body().replaceAll("\"requestId\":\"[0-9a-f]+\"", ""))
                        .isEqualTo(real.body().replaceAll("\"requestId\":\"[0-9a-f]+\"", ""));
            }
        }
        assertThat(count("select count(*) from donation")).isEqualTo(1);
        assertThat(count("select count(*) from idempotency_record")).isEqualTo(1);
    }
}
