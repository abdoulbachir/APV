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

    /**
     * Owner decision of 2026-11-28 (contract 0.4.2): a manual entry takes the optional provider
     * reference of section 7, and it takes part in the rule of 6.3 in both orders.
     */
    @Test
    void d5_1_aManualEntryTakesAProviderReferenceAndOnePaymentIsCountedOnceWhicheverCameFirst() {
        String details = zelle();
        method("paypal", 2);
        Staff owner = owner();

        Map<String, Object> entry = cash("FUND", 1000);
        entry.put("method", map("id", "zelle"));
        entry.put("providerReference", "ZL-ENTRY-1");
        // The request is one the contract's closed schema describes.
        assertThat(contract.violations("DonationEntryRequest", body(entry))).isEmpty();
        JsonNode detail = entered(enter(owner, entry));
        assertThat(detail.at("/decision/providerReference").asText()).isEqualTo("ZL-ENTRY-1");
        assertThat(count("select count(*) from donation where origin = 'staff_entry' and payment_method_id = 'zelle'"
                + " and provider_reference = 'ZL-ENTRY-1'")).isEqualTo(1);
        // Optional: an entry without one is what it was.
        assertThat(entered(enter(owner, cash("FUND", 1100))).at("/decision/providerReference").isNull()).isTrue();

        // The entry first, then a confirmation of a report with that reference: refused, nothing written.
        Reported reported = report("HELP-904", 5000, details);
        String confirmPath = ENTRIES + "/" + giftId(reported.reference()) + "/confirm";
        long entries = count("select count(*) from ledger_entry");
        Map<String, Object> confirmation = confirmationOf("HELP-904", 5000);
        confirmation.put("providerReference", "ZL-ENTRY-1");
        Browser.Answer refused = post(owner.browser(), confirmPath, body(confirmation), key());
        assertError(refused, 409, "duplicate_payment_reference");
        assertThat(refused.body()).doesNotContain(detail.get("id").asText()).doesNotContain(detail.get("reference").asText());
        assertThat(count("select count(*) from ledger_entry")).isEqualTo(entries);
        assertThat(count("select count(*) from donation where state = 'reported'")).isEqualTo(1);

        // A confirmed report first, then an entry with its reference: refused the same way.
        confirmation.put("providerReference", "ZL-REPORT-2");
        assertThat(post(owner.browser(), confirmPath, body(confirmation), key()).status()).isEqualTo(200);
        long gifts = count("select count(*) from donation");
        entries = count("select count(*) from ledger_entry");
        entry.put("providerReference", "ZL-REPORT-2");
        assertError(enter(owner, entry), 409, "duplicate_payment_reference");
        assertThat(count("select count(*) from donation")).isEqualTo(gifts);
        assertThat(count("select count(*) from ledger_entry")).isEqualTo(entries);
        assertThat(count("select count(*) from audit_event where event_type = 'donation.decision_refused'"
                + " and details ->> 'rule' = 'duplicate_payment_reference'")).isEqualTo(2);

        // The rule is per payment method: the same reference in another method's records is another payment.
        entry.put("method", map("id", "paypal"));
        assertThat(entered(enter(owner, entry)).at("/decision/providerReference").asText()).isEqualTo("ZL-REPORT-2");

        // It is text like any other: never a card number, within its length, and the body stays closed around it.
        entry.put("providerReference", "4111 1111 1111 1111");
        assertFieldError(enter(owner, entry), "providerReference", "card_number");
        entry.put("providerReference", "R".repeat(65));
        assertFieldError(enter(owner, entry), "providerReference", "too_long");
        entry.put("providerReference", "");
        assertFieldError(enter(owner, entry), "providerReference", "too_short");
        entry.put("providerReference", 12345);
        assertFieldError(enter(owner, entry), "providerReference", "invalid_type");
        entry.put("providerReference", "ZL-OTHER-3");
        entry.put("ledgerEntryId", "L1");
        assertFieldError(enter(owner, entry), "ledgerEntryId", "unknown_field");
        assertThat(count("select count(*) from donation where provider_reference like '4111%'")).isZero();
    }

    /**
     * A free description ("cash, in person") is not a payment method record, and the rule of 6.3
     * is per payment method: the reference of such an entry is stored and shown to the reviewer,
     * and is compared with nothing.
     */
    @Test
    void d5_1_theReferenceOfAnEntryWithAFreeTextMethodIsStoredAndNotCompared() {
        String details = zelle();
        Staff owner = owner();
        Map<String, Object> entry = cash("FUND", 1000);
        entry.put("providerReference", "RECEIPT-12");
        assertThat(contract.violations("DonationEntryRequest", body(entry))).isEmpty();
        assertThat(entered(enter(owner, entry)).at("/decision/providerReference").asText()).isEqualTo("RECEIPT-12");
        // A second entry described the same way, with the same reference, is recorded: there is no method to scope it to.
        Map<String, Object> other = cash("HELP-904", 2000);
        other.put("method", map("description", "Cheque"));
        other.put("providerReference", "RECEIPT-12");
        assertThat(entered(enter(owner, other)).at("/decision/providerReference").asText()).isEqualTo("RECEIPT-12");
        // And it is not in the way of a method's own records.
        Map<String, Object> confirmation = confirmationOf("HELP-904", 5000);
        confirmation.put("providerReference", "RECEIPT-12");
        assertThat(post(owner.browser(), ENTRIES + "/" + giftId(report("HELP-904", 5000, details).reference()) + "/confirm",
                body(confirmation), key()).status()).isEqualTo(200);
        assertThat(count("select count(*) from donation where provider_reference = 'RECEIPT-12' and state = 'confirmed'")).isEqualTo(3);
    }

    /**
     * The gift a re-entry replaces stays confirmed and keeps its reference, so the re-entry, which
     * is linked to it, does not repeat it: the reference is recorded once for the one payment.
     */
    @Test
    void d5_4_aReentryIsLinkedToTheGiftThatCarriesTheReferenceAndDoesNotRepeatIt() {
        Reported gift = report("HELP-904", 5000, zelle());
        Staff owner = owner();
        Map<String, Object> confirmation = confirmationOf("HELP-904", 5000);
        confirmation.put("providerReference", "ZL-48213907");
        String original = post(owner.browser(), ENTRIES + "/" + giftId(gift.reference()) + "/confirm", body(confirmation), key()).json().get("id").asText();
        assertThat(reverse(owner, original, 5000, "wrong_case").status()).isEqualTo(200);

        Map<String, Object> entry = cash("FUND", 5000);
        entry.put("method", map("id", "zelle"));
        entry.put("reentryOf", original);
        entry.put("providerReference", "ZL-48213907");
        assertError(enter(owner, entry), 409, "duplicate_payment_reference");
        entry.remove("providerReference");
        JsonNode reentry = entered(enter(owner, entry));
        assertThat(reentry.get("reentryOf").asText()).isEqualTo(original);
        assertThat(reentry.at("/decision/providerReference").isNull()).isTrue();
        assertThat(owner.browser().get(ENTRIES + "/" + original).json().at("/decision/providerReference").asText()).isEqualTo("ZL-48213907");
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
