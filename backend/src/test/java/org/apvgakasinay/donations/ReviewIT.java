package org.apvgakasinay.donations;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apvgakasinay.identity.Browser;
import org.junit.jupiter.api.Test;

/** Section 4 of contract/tests/donation-acceptance.md: the review queue, confirming, rejecting and reopening. */
class ReviewIT extends DonationIntegration {

    private String path(Reported gift, String operation) {
        return "/api/admin/donations/" + giftId(gift.reference()) + operation;
    }

    private Browser.Answer confirmWith(Staff owner, Reported gift, Map<String, Object> confirmation) {
        return post(owner.browser(), path(gift, "/confirm"), body(confirmation), key());
    }

    private void assertField(Browser.Answer answer, String field, String code) {
        assertError(answer, 400, "validation_error");
        assertThat(answer.json().at("/error/fields/0/name").asText()).as(answer.body()).isEqualTo(field);
        assertThat(answer.fieldCode()).as(answer.body()).isEqualTo(code);
    }

    private Reported named(String caseCode, long amount, String details, String publicName) {
        Browser guest = browser();
        guest.csrf();
        Map<String, Object> report = reportOf(caseCode, amount, details);
        report.put("display", map("mode", "named", "publicName", publicName));
        return reported(post(guest, "/api/donations", body(report), key()));
    }

    @Test
    void d4_1_theQueueIsOldestFirstWithThePendingCount() {
        String details = zelle();
        Reported first = report("HELP-904", 1000, details);
        advance(Duration.ofMinutes(1));
        Reported second = report("FUND", 2000, details);
        advance(Duration.ofMinutes(1));
        Reported third = report("HELP-901", 3000, details);
        Staff owner = owner();
        owner.browser().post(path(second, "/reject"), body(map("reason", "duplicate")));

        Browser.Answer queue = owner.browser().get("/api/admin/donations");
        assertValid("DonationPage", queue);
        assertThat(queue.header("Cache-Control")).isEqualTo("no-store");
        JsonNode page = queue.json();
        assertThat(page.get("pendingCount").asInt()).isEqualTo(2);
        assertThat(page.get("totalItems").asInt()).isEqualTo(2);
        assertThat(page.get("pageSize").asInt()).isEqualTo(12);
        assertThat(page.at("/items/0/reference").asText()).isEqualTo(first.reference());
        assertThat(page.at("/items/1/reference").asText()).isEqualTo(third.reference());
        assertThat(page.at("/items/0/payerName").asText()).isEqualTo("Sample Donor");
        assertThat(page.at("/items/0/methodId").asText()).isEqualTo("zelle");
        assertThat(page.at("/items/0/evidence").asText()).isEqualTo("none");
        assertThat(page.at("/items/0/reportedAmount").asLong()).isEqualTo(1000);
        assertThat(page.at("/items/0/confirmedAmount").isNull()).isTrue();

        // The other views: the latest decision first, and everything with the latest report first.
        JsonNode rejected = owner.browser().get("/api/admin/donations?status=rejected").json();
        assertThat(rejected.get("totalItems").asInt()).isEqualTo(1);
        assertThat(rejected.at("/items/0/reference").asText()).isEqualTo(second.reference());
        assertThat(rejected.get("pendingCount").asInt()).isEqualTo(2);
        JsonNode all = owner.browser().get("/api/admin/donations?status=all&pageSize=2").json();
        assertThat(all.get("totalItems").asInt()).isEqualTo(3);
        assertThat(all.get("totalPages").asInt()).isEqualTo(2);
        assertThat(all.at("/items/0/reference").asText()).isEqualTo(third.reference());
        // Narrowed to one case, in any letter case, and to one reference, with or without its dash.
        JsonNode fund = owner.browser().get("/api/admin/donations?status=all&caseCode=fund").json();
        assertThat(fund.get("totalItems").asInt()).isEqualTo(1);
        assertThat(fund.at("/items/0/caseCode").asText()).isEqualTo("FUND");
        JsonNode one = owner.browser().get("/api/admin/donations?status=all&reference=" + third.reference().replace("-", "")).json();
        assertThat(one.get("totalItems").asInt()).isEqualTo(1);
        assertThat(one.at("/items/0/reference").asText()).isEqualTo(third.reference());

        assertField(owner.browser().get("/api/admin/donations?status=pending"), "status", "invalid_value");
        assertField(owner.browser().get("/api/admin/donations?pageSize=49"), "pageSize", "out_of_range");
        assertField(owner.browser().get("/api/admin/donations?reference=nope"), "reference", "invalid_format");
        assertField(owner.browser().get("/api/admin/donations?caseCode=not%20a%20code"), "caseCode", "invalid_format");
    }

    @Test
    void d4_2_d4_3_confirmingWritesOneLedgerEntryAndPublicTotalsChangeAtThatInstantAndNotBefore() {
        Reported gift = named("HELP-904", 5000, zelle(), "Sample D.");
        Staff owner = owner();
        long received = publicCase("HELP-904").at("/case/received").asLong();
        long platform = browser().get("/api/stats").json().get("received").asLong();
        long entries = count("select count(*) from ledger_entry");

        Map<String, Object> confirmation = confirmationOf("HELP-904", 5000);
        confirmation.put("providerReference", "ZL-48213907");
        Browser.Answer answer = confirmWith(owner, gift, confirmation);
        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        assertValid("DonationDetail", answer);
        JsonNode detail = answer.json();
        assertThat(detail.get("state").asText()).isEqualTo("confirmed");
        assertThat(detail.at("/decision/state").asText()).isEqualTo("confirmed");
        assertThat(detail.at("/decision/caseCode").asText()).isEqualTo("HELP-904");
        assertThat(detail.at("/decision/amount").asLong()).isEqualTo(5000);
        assertThat(detail.at("/decision/by/displayName").asText()).isEqualTo("Sample Staff");
        assertThat(detail.at("/decision/listing").asText()).isEqualTo("named");
        assertThat(detail.get("netAmount").asLong()).isEqualTo(5000);
        assertThat(detail.at("/history/1/action").asText()).isEqualTo("confirmed");

        // Exactly one donation entry, on that case, for that amount, dated at the instant of the confirmation.
        assertThat(count("select count(*) from ledger_entry")).isEqualTo(entries + 1);
        JsonNode ledger = publicCase("HELP-904").at("/ledger/0");
        assertThat(ledger.get("id").asText()).isEqualTo(detail.get("ledgerEntryId").asText());
        assertThat(ledger.get("kind").asText()).isEqualTo("donation");
        assertThat(ledger.get("amount").asLong()).isEqualTo(5000);
        assertThat(ledger.get("allocatedDelta").asLong()).isEqualTo(5000);
        assertThat(ledger.get("deliveredDelta").asLong()).isZero();
        assertThat(ledger.get("date").asText()).isEqualTo(detail.at("/decision/at").asText());
        // The label is the kind's: no name, no method, no reference.
        assertThat(ledger.toString()).doesNotContain("Sample").doesNotContain("zelle").doesNotContain("ZL-");

        assertThat(publicCase("HELP-904").at("/case/received").asLong()).isEqualTo(received + 5000);
        assertThat(browser().get("/api/stats").json().get("received").asLong()).isEqualTo(platform + 5000);
        JsonNode donor = publicCase("HELP-904").at("/donors/0");
        assertThat(donor.get("name").asText()).isEqualTo("Sample D.");
        assertThat(donor.get("amount").asLong()).isEqualTo(5000);
        // The public identifiers are unrelated to the gift's identifier and reference (1.3).
        assertThat(publicCase("HELP-904").toString()).doesNotContain(gift.reference()).doesNotContain(detail.get("id").asText());
    }

    @Test
    void d4_4_d4_5_aGiftCanBeConfirmedForAnotherCaseWhenTheReviewerSaysWhy() {
        Reported gift = report("HELP-904", 5000, zelle());
        Staff owner = owner();
        assertField(confirmWith(owner, gift, confirmationOf("FUND", 5000)), "note", "required");
        assertThat(count("select count(*) from donation where state = 'confirmed'")).isZero();

        Map<String, Object> confirmation = confirmationOf("fund", 4500);
        confirmation.put("note", "The donor asked for the general fund by message.");
        JsonNode detail = confirmWith(owner, gift, confirmation).json();
        // The report keeps the case and the amount the donor stated; the confirmation has its own.
        assertThat(detail.at("/reported/caseCode").asText()).isEqualTo("HELP-904");
        assertThat(detail.at("/reported/amount").asLong()).isEqualTo(5000);
        assertThat(detail.at("/decision/caseCode").asText()).isEqualTo("FUND");
        assertThat(detail.at("/decision/amount").asLong()).isEqualTo(4500);
        assertThat(detail.get("flags").toString()).contains("different_case");
        assertThat(publicCase("FUND").at("/ledger/0/amount").asLong()).isEqualTo(4500);
        assertThat(publicCase("HELP-904").at("/case/received").asLong()).isZero();

        // The donor's status page shows both.
        Browser guest = browser();
        guest.csrf();
        JsonNode status = status(guest, gift.trackingCode()).json();
        assertThat(status.get("caseCode").asText()).isEqualTo("HELP-904");
        assertThat(status.get("reportedAmount").asLong()).isEqualTo(5000);
        assertThat(status.get("confirmedCaseCode").asText()).isEqualTo("FUND");
        assertThat(status.get("confirmedAmount").asLong()).isEqualTo(4500);
    }

    @Test
    void d4_6_d4_15_aDecisionNeedsTheRightStateAndAConfirmationIsNeverUndone() {
        String details = zelle();
        Reported gift = report("HELP-904", 5000, details);
        Staff owner = owner();
        confirm(owner, gift, "HELP-904", 5000);
        long entries = count("select count(*) from ledger_entry");

        assertError(confirmWith(owner, gift, confirmationOf("HELP-904", 5000)), 409, "donation_state_conflict");
        assertError(owner.browser().post(path(gift, "/reject"), body(map("reason", "duplicate"))), 409, "donation_state_conflict");
        assertError(owner.browser().postEmpty(path(gift, "/reopen")), 409, "donation_state_conflict");
        assertThat(count("select count(*) from ledger_entry")).isEqualTo(entries);
        assertThat(count("select count(*) from audit_event where event_type = 'donation.decision_refused' and details ->> 'rule' = 'state'")).isEqualTo(1);

        // A gift that does not exist, and one whose identifier is not an identifier.
        assertError(post(owner.browser(), "/api/admin/donations/don_AAAAAAAAAAAAAAAAAAAAAA/confirm", body(confirmationOf("HELP-904", 1)), key()),
                404, "donation_not_found");
        Reported rejected = report("HELP-904", 700, details);
        owner.browser().post(path(rejected, "/reject"), body(map("reason", "not_received")));
        assertError(confirmWith(owner, rejected, confirmationOf("HELP-904", 700)), 409, "donation_state_conflict");
        // The database refuses it too, whatever the application does.
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbc.sql("update donation set state = 'reported' where state = 'confirmed'").update()).hasMessageContaining("never unconfirmed");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("delete from donation").update()).hasMessageContaining("never deleted");
    }

    @Test
    void d4_7_ofTwoConfirmationsOfOneGiftAtTheSameMomentExactlyOneSucceeds() {
        Reported gift = report("HELP-904", 5000, zelle());
        Staff owner = owner();
        long entries = count("select count(*) from ledger_entry");
        Browser one = owner.browser();
        Browser two = sameSession(owner.browser());
        String path = path(gift, "/confirm");
        List<Browser.Answer> answers = together(List.of(
                () -> post(one, path, body(confirmationOf("HELP-904", 5000)), key()),
                () -> post(two, path, body(confirmationOf("HELP-904", 5000)), key())));
        assertThat(answers.stream().map(Browser.Answer::status).sorted().toList()).isEqualTo(List.of(200, 409));
        assertThat(answers.stream().filter(answer -> answer.status() == 409).findFirst().orElseThrow().code()).isEqualTo("donation_state_conflict");
        assertThat(count("select count(*) from ledger_entry")).isEqualTo(entries + 1);
        assertThat(count("select count(*) from donation_listing l join donation d on d.ledger_entry_id = l.ledger_entry_id")).isEqualTo(1);
        assertThat(publicCase("HELP-904").at("/case/received").asLong()).isEqualTo(5000);
    }

    @Test
    void d4_8_oneProviderReferenceIsCountedOnceForAMethod() {
        String zelle = zelle();
        String paypal = method("paypal", 2);
        Reported first = report("HELP-904", 5000, zelle);
        Reported second = report("HELP-904", 5000, zelle);
        Reported other = report("HELP-904", 5000, paypal);
        Staff owner = owner();
        Map<String, Object> confirmation = confirmationOf("HELP-904", 5000);
        confirmation.put("providerReference", "ZL-48213907");
        assertThat(confirmWith(owner, first, confirmation).status()).isEqualTo(200);
        long entries = count("select count(*) from ledger_entry");

        Browser.Answer duplicate = confirmWith(owner, second, confirmation);
        assertError(duplicate, 409, "duplicate_payment_reference");
        // It names nothing: not the other gift, not its reference.
        assertThat(duplicate.body()).doesNotContain(first.reference()).doesNotContain(giftId(first.reference()));
        assertThat(count("select count(*) from ledger_entry")).isEqualTo(entries);
        assertThat(count("select count(*) from donation where state = 'reported'")).isEqualTo(2);

        // The same reference in another method's records is another payment.
        assertThat(confirmWith(owner, other, confirmation).status()).isEqualTo(200);
        // And without a reference, the second report can be confirmed: the duty to check is the reviewer's.
        assertThat(confirmWith(owner, second, confirmationOf("HELP-904", 5000)).status()).isEqualTo(200);
    }

    @Test
    void d4_9_noMoneyIsConfirmedOnADraftOrAnUnknownCaseButAClosedCaseStillReceivesWhatArrived() {
        Reported gift = report("HELP-904", 5000, zelle());
        Staff owner = owner();
        assertField(confirmWith(owner, gift, confirmationOf("HELP-902", 5000)), "caseCode", "invalid_value");
        assertField(confirmWith(owner, gift, confirmationOf("ZZZ-999", 5000)), "caseCode", "invalid_value");
        assertField(confirmWith(owner, gift, confirmationOf("HELP-904", 0)), "confirmedAmount", "out_of_range");
        Map<String, Object> late = confirmationOf("HELP-904", 5000);
        late.put("receivedOn", "2025-11-22");
        assertField(confirmWith(owner, gift, late), "receivedOn", "out_of_range");
        late.put("receivedOn", "2026-11-26");
        assertField(confirmWith(owner, gift, late), "receivedOn", "out_of_range");
        Map<String, Object> prefilled = confirmationOf("HELP-904", 5000);
        prefilled.remove("confirmedAmount");
        // The amount and the case are always stated: nothing is taken from the report.
        assertField(confirmWith(owner, gift, prefilled), "confirmedAmount", "required");
        prefilled = confirmationOf("HELP-904", 5000);
        prefilled.remove("caseCode");
        assertField(confirmWith(owner, gift, prefilled), "caseCode", "required");
        prefilled = confirmationOf("HELP-904", 5000);
        prefilled.put("state", "confirmed");
        assertField(confirmWith(owner, gift, prefilled), "state", "unknown_field");
        assertThat(count("select count(*) from donation where state = 'confirmed'")).isZero();

        // The case does not have to be accepting gifts now: the donor acted when it was, and the money has arrived.
        jdbc.sql("update aid_case set accepts_donations = false where code = 'HELP-904'").update();
        assertThat(confirmWith(owner, gift, confirmationOf("HELP-904", 5000)).status()).isEqualTo(200);
    }

    @Test
    void d4_10_d4_11_theReviewerCanShowLessThanTheDonorAskedAndNeverMore() {
        String details = zelle();
        Staff owner = owner();

        Reported named = named("HELP-904", 1000, details, "Unsuitable Name");
        Map<String, Object> anonymous = confirmationOf("HELP-904", 1000);
        anonymous.put("listing", "anonymous");
        JsonNode detail = confirmWith(owner, named, anonymous).json();
        assertThat(detail.at("/decision/listing").asText()).isEqualTo("anonymous");
        assertThat(detail.at("/display/mode").asText()).isEqualTo("anonymous");
        assertThat(detail.at("/display/publicName").isNull()).isTrue();
        JsonNode publicView = publicCase("HELP-904");
        assertThat(publicView.at("/counts/donors").asInt()).isEqualTo(1);
        assertThat(publicView.toString()).doesNotContain("Unsuitable Name");
        assertThat(count("select count(*) from donation_listing where donor_name is not null and ledger_entry_id in (select ledger_entry_id from donation)")).isZero();

        // Asked to be hidden: counted, never listed, whatever the reviewer chooses.
        Browser guest = browser();
        guest.csrf();
        Map<String, Object> hiddenReport = reportOf("HELP-904", 2000, details);
        hiddenReport.put("display", map("mode", "hidden"));
        Reported hidden = reported(post(guest, "/api/donations", body(hiddenReport), key()));
        assertThat(confirmWith(owner, hidden, confirmationOf("HELP-904", 2000)).json().at("/decision/listing").asText()).isEqualTo("hidden");
        Reported hiddenToo = reported(post(guest, "/api/donations", body(hiddenReport), key()));
        Map<String, Object> more = confirmationOf("HELP-904", 2000);
        more.put("listing", "anonymous");
        assertThat(confirmWith(owner, hiddenToo, more).json().at("/decision/listing").asText()).isEqualTo("hidden");
        publicView = publicCase("HELP-904");
        assertThat(publicView.at("/case/received").asLong()).isEqualTo(5000);
        assertThat(publicView.at("/counts/donors").asInt()).isEqualTo(1);

        Map<String, Object> invented = confirmationOf("HELP-904", 2000);
        invented.put("listing", "named");
        assertField(confirmWith(owner, report("HELP-904", 2000, details), invented), "listing", "invalid_value");
    }

    @Test
    void d4_12_d4_13_rejectingChangesNothingPublicAndTheDonorSeesTheReasonNeverTheNote() {
        Reported gift = report("HELP-904", 5000, zelle());
        Staff owner = owner();
        String before = publicCase("HELP-904").toString();

        assertField(owner.browser().post(path(gift, "/reject"), body(map("reason", "other"))), "note", "required");
        assertField(owner.browser().post(path(gift, "/reject"), body(map("reason", "fraud"))), "reason", "invalid_value");
        assertField(owner.browser().post(path(gift, "/reject"), body(map())), "reason", "required");

        Browser.Answer rejected = owner.browser().post(path(gift, "/reject"),
                body(map("reason", "not_received", "note", "No payment from this name in the last two weeks.")));
        assertThat(rejected.status()).as(rejected.body()).isEqualTo(200);
        assertValid("DonationDetail", rejected);
        assertThat(rejected.json().at("/decision/state").asText()).isEqualTo("rejected");
        assertThat(rejected.json().at("/decision/rejectionReason").asText()).isEqualTo("not_received");
        assertThat(rejected.json().at("/decision/note").asText()).startsWith("No payment");
        assertThat(rejected.json().at("/decision/caseCode").isNull()).isTrue();
        assertThat(rejected.json().get("ledgerEntryId").isNull()).isTrue();
        assertThat(publicCase("HELP-904").toString()).isEqualTo(before);

        Browser guest = browser();
        guest.csrf();
        Browser.Answer status = status(guest, gift.trackingCode());
        assertValid("DonationStatus", status);
        assertThat(status.json().get("state").asText()).isEqualTo("rejected");
        assertThat(status.json().get("rejectionReason").asText()).isEqualTo("not_received");
        assertThat(status.json().get("decidedAt").asText()).isEqualTo(rejected.json().at("/decision/at").asText());
        assertThat(status.body()).doesNotContain("No payment").doesNotContain("Sample Staff").doesNotContain("Sample Donor");
    }

    @Test
    void d4_14_reopeningPutsARejectedGiftBackInTheQueueAndKeepsTheRejectionInItsHistory() {
        Reported gift = report("HELP-904", 5000, zelle());
        Staff owner = owner();
        owner.browser().post(path(gift, "/reject"), body(map("reason", "other", "note", "Could not find it.")));
        assertThat(owner.browser().get("/api/admin/donations").json().get("pendingCount").asInt()).isZero();

        Browser.Answer reopened = owner.browser().postEmpty(path(gift, "/reopen"));
        assertThat(reopened.status()).as(reopened.body()).isEqualTo(200);
        assertValid("DonationDetail", reopened);
        assertThat(reopened.json().get("state").asText()).isEqualTo("reported");
        assertThat(reopened.json().get("decision").isNull()).isTrue();
        List<String> actions = new java.util.ArrayList<>();
        reopened.json().get("history").forEach(entry -> actions.add(entry.get("action").asText()));
        assertThat(actions).containsExactly("reported", "rejected", "reopened");
        assertThat(owner.browser().get("/api/admin/donations").json().get("pendingCount").asInt()).isEqualTo(1);
        assertThat(status(browserWithToken(), gift.trackingCode()).json().get("state").asText()).isEqualTo("reported");

        // A body is not part of the operation, and a second reopening has nothing to reopen.
        assertError(owner.browser().post(path(gift, "/reopen"), "{}"), 400, "validation_error");
        assertError(owner.browser().postEmpty(path(gift, "/reopen")), 409, "donation_state_conflict");
        // The payment arrived after all.
        confirm(owner, gift, "HELP-904", 5000);
    }

    private Browser browserWithToken() {
        Browser browser = browser();
        browser.csrf();
        return browser;
    }

    @Test
    void d4_16_aConfirmationThatReachesTheGoalMakesTheCaseFundedInTheSameTransaction() {
        String details = zelle();
        Staff owner = owner();
        // HELP-904 asks for 600.00 and has received nothing.
        confirm(owner, report("HELP-904", 59_999, details), "HELP-904", 59_999);
        assertThat(publicCase("HELP-904").at("/case/status").asText()).isEqualTo("active");
        JsonNode detail = confirm(owner, report("HELP-904", 1, details), "HELP-904", 1);
        assertThat(publicCase("HELP-904").at("/case/status").asText()).isEqualTo("funded");
        assertThat(count("select count(*) from audit_event where event_type = 'case.status_changed' and details ->> 'case' = 'HELP-904'"
                + " and details ->> 'from' = 'active' and details ->> 'to' = 'funded' and details ->> 'ledger_entry' = '"
                + detail.get("ledgerEntryId").asText() + "'")).isEqualTo(1);
        // Gifts beyond the goal are still welcome, and the fund has no goal and never changes status.
        confirm(owner, report("HELP-904", 500, details), "HELP-904", 500);
        confirm(owner, report("FUND", 900_000, details), "FUND", 900_000);
        assertThat(publicCase("HELP-904").at("/case/status").asText()).isEqualTo("funded");
        assertThat(publicCase("FUND").at("/case/status").asText()).isEqualTo("active");
        assertThat(count("select count(*) from audit_event where event_type = 'case.status_changed'")).isEqualTo(1);

        // Money confirmed on a delivered case changes no status; the case is marked for review.
        confirm(owner, report("HELP-904", 300, details), "HELP-903", 300, "Arrived after the handover.");
        assertThat(publicCase("HELP-903").at("/case/status").asText()).isEqualTo("delivered");
        assertThat(count("select count(*) from audit_event where event_type = 'case.review_needed' and details ->> 'case' = 'HELP-903'")).isEqualTo(1);
    }

    private JsonNode confirm(Staff owner, Reported gift, String caseCode, long amount, String note) {
        Map<String, Object> confirmation = confirmationOf(caseCode, amount);
        confirmation.put("note", note);
        Browser.Answer answer = confirmWith(owner, gift, confirmation);
        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        return answer.json();
    }

    @Test
    void d4_17_aConfirmationThatWouldPassThePlatformBoundWritesNothing() {
        Reported gift = report("HELP-904", 5000, zelle());
        Staff owner = owner();
        jdbc.sql("update ledger_platform_total set gross_cents = 1000000000000000 - 4999").update();
        long entries = count("select count(*) from ledger_entry");

        assertError(confirmWith(owner, gift, confirmationOf("HELP-904", 5000)), 409, "platform_limit_reached");
        assertThat(count("select count(*) from ledger_entry")).isEqualTo(entries);
        assertThat(count("select count(*) from donation where state = 'reported'")).isEqualTo(1);
        assertThat(count("select count(*) from audit_event where event_type = 'donation.decision_refused' and details ->> 'rule' = 'platform_limit_reached'")).isEqualTo(1);
        assertThat(confirmWith(owner, gift, confirmationOf("HELP-904", 4999)).status()).isEqualTo(200);
    }

    @Test
    void d4_18_aConfirmationSentTwiceWithOneKeyWritesOneEntryAndAnswersTheSame() {
        String details = zelle();
        Reported gift = report("HELP-904", 5000, details);
        Reported other = report("HELP-904", 5000, details);
        Staff owner = owner();
        String key = key();
        String sent = body(confirmationOf("HELP-904", 5000));
        long entries = count("select count(*) from ledger_entry");

        Browser.Answer first = post(owner.browser(), path(gift, "/confirm"), sent, key);
        Browser.Answer again = post(owner.browser(), path(gift, "/confirm"), sent, key);
        assertThat(first.status()).isEqualTo(200);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body()).isEqualTo(first.body());
        assertThat(count("select count(*) from ledger_entry")).isEqualTo(entries + 1);
        assertThat(count("select count(*) from audit_event where event_type = 'donation.confirmed'")).isEqualTo(1);

        // The same key with another body, another gift or another operation is a key used twice.
        assertError(post(owner.browser(), path(gift, "/confirm"), body(confirmationOf("HELP-904", 5001)), key), 409, "idempotency_key_reused");
        assertError(post(owner.browser(), path(other, "/confirm"), sent, key), 409, "idempotency_key_reused");
        assertError(post(owner.browser(), path(gift, "/reversals"), body(map("amount", 100, "reason", "other", "note", "Mistake.")), key),
                409, "idempotency_key_reused");
        assertThat(count("select count(*) from ledger_entry")).isEqualTo(entries + 1);
        // A key belongs to the account that used it: without one the operation is not attempted.
        assertField(post(owner.browser(), path(other, "/confirm"), sent, null), "Idempotency-Key", "required");
    }

    @Test
    void d4_19_theDetailListsPossibleDuplicatesAsHintsThatDecideNothing() {
        String details = zelle();
        Reported first = report("HELP-904", 5000, details);
        advance(Duration.ofDays(2));
        // The same payment reported twice: same method, amount and payer name, differing only in case and spacing.
        Browser guest = browserWithToken();
        Map<String, Object> again = reportOf("FUND", 5000, currentDetails("zelle"));
        again.put("payerName", "sample  DONOR");
        again.put("paidOn", "2026-11-26");
        Reported second = reported(post(guest, "/api/donations", body(again), key()));
        // Not the same: another amount, another name, and one reported eight days later.
        Map<String, Object> otherAmount = reportOf("HELP-904", 5001, currentDetails("zelle"));
        otherAmount.put("paidOn", "2026-11-26");
        reported(post(browserWithToken(), "/api/donations", body(otherAmount), key()));
        advance(Duration.ofDays(6));
        Map<String, Object> late = reportOf("HELP-904", 5000, currentDetails("zelle"));
        late.put("paidOn", "2026-12-01");
        Reported third = reported(post(browserWithToken(), "/api/donations", body(late), key()));

        Staff owner = owner();
        JsonNode detail = owner.browser().get(path(first, "")).json();
        assertThat(detail.get("possibleDuplicates")).hasSize(1);
        assertThat(detail.at("/possibleDuplicates/0/reference").asText()).isEqualTo(second.reference());
        assertThat(detail.at("/possibleDuplicates/0/amount").asLong()).isEqualTo(5000);
        assertThat(detail.get("flags").toString()).contains("possible_duplicate");
        // Within seven days of the second, both the first and the third look like it.
        assertThat(owner.browser().get(path(second, "")).json().get("possibleDuplicates")).hasSize(2);
        assertThat(owner.browser().get(path(third, "")).json().get("possibleDuplicates")).hasSize(1);
        JsonNode queue = owner.browser().get("/api/admin/donations").json();
        assertThat(queue.at("/items/0/flags").toString()).contains("possible_duplicate");

        // A hint refuses nothing: both can be confirmed, and the reviewer is the one who decides.
        confirm(owner, first, "HELP-904", 5000);
        Map<String, Object> confirmation = confirmationOf("FUND", 5000);
        confirmation.put("receivedOn", "2026-12-01");
        assertThat(post(owner.browser(), path(second, "/confirm"), body(confirmation), key()).status()).isEqualTo(200);
    }
}
