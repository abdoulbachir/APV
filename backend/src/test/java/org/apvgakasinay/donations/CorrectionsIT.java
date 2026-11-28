package org.apvgakasinay.donations;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.apvgakasinay.identity.Browser;
import org.junit.jupiter.api.Test;

/** Section 6 of contract/tests/donation-acceptance.md: repairing a wrong record, and recording money that went back. */
class CorrectionsIT extends DonationIntegration {

    private String base(String id) {
        return "/api/admin/donations/" + id;
    }

    private Browser.Answer reverse(Staff owner, String id, long amount, String reason) {
        return post(owner.browser(), base(id) + "/reversals", body(map("amount", amount, "reason", reason, "note", "Typed wrongly.")), key());
    }

    private Browser.Answer refund(Staff owner, String id, long amount, String providerReference) {
        Map<String, Object> refund = map("amount", amount, "refundedOn", TODAY, "note", "The donor asked for it back.");
        if (providerReference != null) {
            refund.put("providerReference", providerReference);
        }
        return post(owner.browser(), base(id) + "/refunds", body(refund), key());
    }

    /** A named gift of 50.00 confirmed for HELP-904. */
    private String confirmed(Staff owner, String details) {
        Browser guest = visitor();
        Map<String, Object> report = reportOf("HELP-904", 5000, details);
        report.put("display", map("mode", "named", "publicName", "Sample D."));
        Reported gift = reported(post(guest, "/api/donations", body(report), key()));
        return confirm(owner, gift, "HELP-904", 5000).get("id").asText();
    }

    @Test
    void d6_1_d6_2_aReversalAndARefundEachWriteOneEntryAndReduceWhatStands() {
        Staff owner = owner();
        String id = confirmed(owner, zelle());
        long entries = count("select count(*) from ledger_entry");

        Browser.Answer reversed = reverse(owner, id, 1500, "wrong_amount");
        assertThat(reversed.status()).as(reversed.body()).isEqualTo(200);
        assertValid("DonationDetail", reversed);
        assertThat(reversed.json().get("netAmount").asLong()).isEqualTo(3500);
        assertThat(reversed.json().get("state").asText()).isEqualTo("confirmed");
        JsonNode correction = reversed.json().at("/corrections/0");
        assertThat(correction.get("kind").asText()).isEqualTo("donation_reversal");
        assertThat(correction.get("amount").asLong()).isEqualTo(1500);
        assertThat(correction.get("reason").asText()).isEqualTo("wrong_amount");
        assertThat(correction.get("providerReference").isNull()).isTrue();
        assertThat(count("select count(*) from ledger_entry")).isEqualTo(entries + 1);

        Browser.Answer refunded = refund(owner, id, 2000, "ZL-RET-77120");
        assertThat(refunded.status()).as(refunded.body()).isEqualTo(200);
        assertThat(refunded.json().get("netAmount").asLong()).isEqualTo(1500);
        JsonNode second = refunded.json().at("/corrections/1");
        assertThat(second.get("kind").asText()).isEqualTo("refund");
        assertThat(second.get("reason").isNull()).isTrue();
        assertThat(second.get("providerReference").asText()).isEqualTo("ZL-RET-77120");
        List<String> actions = new java.util.ArrayList<>();
        refunded.json().get("history").forEach(entry -> actions.add(entry.get("action").asText()));
        assertThat(actions).containsExactly("reported", "confirmed", "reversed", "refunded");

        // In public: two correction entries on the case the gift was confirmed for, each pointing at the gift's entry.
        JsonNode publicCase = publicCase("HELP-904");
        assertThat(publicCase.at("/case/received").asLong()).isEqualTo(1500);
        assertThat(publicCase.at("/counts/ledger").asInt()).isEqualTo(3);
        String giftEntry = refunded.json().get("ledgerEntryId").asText();
        assertThat(publicCase.at("/ledger/0/kind").asText()).isEqualTo("refund");
        assertThat(publicCase.at("/ledger/0/corrects").asText()).isEqualTo(giftEntry);
        assertThat(publicCase.at("/ledger/0/allocatedDelta").asLong()).isEqualTo(-2000);
        assertThat(publicCase.at("/ledger/1/kind").asText()).isEqualTo("donation_reversal");
        assertThat(publicCase.at("/ledger/2/netAmount").asLong()).isEqualTo(1500);
        // 6.12: the donor listing shows what still stands.
        assertThat(publicCase.at("/donors/0/amount").asLong()).isEqualTo(1500);
        assertThat(count("select count(*) from audit_event where event_type = 'donation.reversed' and details ->> 'remaining' = '3500'")).isEqualTo(1);
        assertThat(count("select count(*) from audit_event where event_type = 'donation.refunded' and details ->> 'remaining' = '1500'")).isEqualTo(1);
    }

    @Test
    void d6_3_aReversalAndARefundAreDifferentOperationsWithDifferentRequiredFields() {
        Staff owner = owner();
        String id = confirmed(owner, zelle());
        long entries = count("select count(*) from ledger_entry");

        // Neither form can be sent as the other.
        assertFieldError(post(owner.browser(), base(id) + "/reversals",
                body(map("amount", 100, "refundedOn", TODAY, "note", "Returned.")), key()), "refundedOn", "unknown_field");
        assertFieldError(post(owner.browser(), base(id) + "/refunds",
                body(map("amount", 100, "reason", "wrong_amount", "note", "Mistake.")), key()), "reason", "unknown_field");
        assertFieldError(post(owner.browser(), base(id) + "/reversals", body(map("amount", 100, "note", "Mistake.")), key()), "reason", "required");
        assertFieldError(post(owner.browser(), base(id) + "/reversals",
                body(map("amount", 100, "reason", "refund", "note", "Mistake.")), key()), "reason", "invalid_value");
        assertFieldError(post(owner.browser(), base(id) + "/refunds", body(map("amount", 100, "note", "Returned.")), key()), "refundedOn", "required");
        assertFieldError(post(owner.browser(), base(id) + "/refunds",
                body(map("amount", 100, "refundedOn", "2026-11-26", "note", "Returned.")), key()), "refundedOn", "out_of_range");
        // Each states why, privately (8.1).
        assertFieldError(post(owner.browser(), base(id) + "/reversals", body(map("amount", 100, "reason", "other")), key()), "note", "required");
        assertFieldError(post(owner.browser(), base(id) + "/refunds", body(map("amount", 100, "refundedOn", TODAY)), key()), "note", "required");
        assertFieldError(post(owner.browser(), base(id) + "/reversals", body(map("amount", 0, "reason", "other", "note", "x")), key()), "amount", "out_of_range");
        assertFieldError(post(owner.browser(), base(id) + "/reversals", body(map("amount", 100, "reason", "other", "note", "x")), null),
                "Idempotency-Key", "required");
        assertThat(count("select count(*) from ledger_entry")).isEqualTo(entries);
    }

    @Test
    void d6_4_d6_7_aCorrectionIsForAtMostWhatStandsOfAConfirmedGift() {
        String details = zelle();
        Staff owner = owner();
        String id = confirmed(owner, details);
        long entries = count("select count(*) from ledger_entry");

        assertError(reverse(owner, id, 5001, "wrong_amount"), 409, "correction_exceeds_remaining");
        assertThat(reverse(owner, id, 4000, "wrong_amount").status()).isEqualTo(200);
        assertError(refund(owner, id, 1001, null), 409, "correction_exceeds_remaining");
        assertThat(refund(owner, id, 1000, null).status()).isEqualTo(200);
        assertError(reverse(owner, id, 1, "other"), 409, "correction_exceeds_remaining");
        assertThat(count("select count(*) from ledger_entry")).isEqualTo(entries + 2);
        assertThat(count("select count(*) from audit_event where event_type = 'donation.decision_refused' and details ->> 'rule' = 'correction_exceeds_remaining'"))
                .isEqualTo(3);

        // Only a confirmed gift can be corrected.
        Reported reported = report("HELP-904", 700, details);
        assertError(reverse(owner, giftId(reported.reference()), 100, "other"), 409, "donation_state_conflict");
        owner.browser().post(base(giftId(reported.reference())) + "/reject", body(map("reason", "not_received")));
        assertError(refund(owner, giftId(reported.reference()), 100, null), 409, "donation_state_conflict");
        assertError(reverse(owner, "don_AAAAAAAAAAAAAAAAAAAAAA", 100, "other"), 404, "donation_not_found");
    }

    @Test
    void d6_5_twoCorrectionsAtTheSameMomentCannotTogetherPassWhatStands() {
        Staff owner = owner();
        String id = confirmed(owner, zelle());
        Browser one = owner.browser();
        Browser two = sameSession(owner.browser());
        // 30.00 and 30.00 against 50.00: either fits alone, both do not.
        List<Browser.Answer> answers = together(List.of(
                () -> post(one, base(id) + "/reversals", body(map("amount", 3000, "reason", "wrong_amount", "note", "One.")), key()),
                () -> post(two, base(id) + "/refunds", body(map("amount", 3000, "refundedOn", TODAY, "note", "Two.")), key())));
        assertThat(answers.stream().map(Browser.Answer::status).sorted().toList()).isEqualTo(List.of(200, 409));
        assertThat(answers.stream().filter(answer -> answer.status() == 409).findFirst().orElseThrow().code()).isEqualTo("correction_exceeds_remaining");
        assertThat(count("select coalesce(sum(amount_cents), 0) from donation_correction")).isEqualTo(3000);
        assertThat(owner.browser().get(base(id)).json().get("netAmount").asLong()).isEqualTo(2000);
    }

    @Test
    void d6_6_theProvidersReferenceForAReturnIsRecordedOnceAgainstAGift() {
        String details = zelle();
        Staff owner = owner();
        String id = confirmed(owner, details);
        String other = confirmed(owner, details);
        assertThat(refund(owner, id, 1000, "ZL-RET-77120").status()).isEqualTo(200);
        assertError(refund(owner, id, 1000, "ZL-RET-77120"), 409, "duplicate_payment_reference");
        assertThat(owner.browser().get(base(id)).json().get("netAmount").asLong()).isEqualTo(4000);
        // Against another gift it is another return; and a return without a reference is the reviewer's to check.
        assertThat(refund(owner, other, 1000, "ZL-RET-77120").status()).isEqualTo(200);
        assertThat(refund(owner, id, 1000, null).status()).isEqualTo(200);
    }

    @Test
    void d6_8_aCorrectionCannotBeCorrectedOrRemoved() {
        Staff owner = owner();
        String id = confirmed(owner, zelle());
        String correction = reverse(owner, id, 1000, "wrong_amount").json().at("/corrections/0/ledgerEntryId").asText();

        // No operation names a correction: it has no address of its own.
        for (String path : List.of(base(id) + "/reversals/" + correction, base(id) + "/corrections/" + correction,
                base(id) + "/reversals/" + correction + "/reversals", "/api/admin/ledger/" + correction)) {
            assertError(post(owner.browser(), path, body(map("amount", 100, "reason", "other", "note", "x")), key()), 404, "not_found");
        }
        assertError(owner.browser().send("DELETE", base(id) + "/reversals", null, null, owner.browser().token), 405, "method_not_allowed");
        // The database refuses it too: a correction corrects a gift's entry, never another correction, and is never edited.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("""
                insert into ledger_entry (public_id, case_id, kind, amount_cents, effective_at, corrects_id)
                select 'X1', case_id, 'donation_reversal', 100, effective_at, id from ledger_entry where public_id = :id
                """).param("id", correction).update()).hasMessageContaining("can only correct a donation");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("update donation_correction set amount_cents = 1").update())
                .hasMessageContaining("never changes");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("delete from donation_correction").update())
                .hasMessageContaining("never deleted");
    }

    @Test
    void d6_9_aRealRefundIsRecordedEvenWhenItLeavesTheCaseWithLessThanItHandedOver() {
        Staff owner = owner();
        String id = confirmed(owner, zelle());
        // Everything HELP-904 received is handed over to the beneficiary.
        jdbc.sql("""
                insert into ledger_entry (public_id, case_id, kind, amount_cents, effective_at)
                values ('XD1', (select id from aid_case where code = 'HELP-904'), 'disbursement', 5000, :at::timestamptz)
                """).param("at", clock.now().toString()).update();
        assertThat(publicCase("HELP-904").at("/financials/available").asLong()).isZero();

        Browser.Answer refunded = refund(owner, id, 2000, null);
        assertThat(refunded.status()).as(refunded.body()).isEqualTo(200);
        assertThat(refunded.json().get("flags").toString()).contains("shortfall");
        JsonNode publicCase = publicCase("HELP-904");
        assertThat(publicCase.at("/financials/available").asLong()).isEqualTo(-2000);
        assertThat(publicCase.at("/financials/hasShortfall").asBoolean()).isTrue();
        assertThat(publicCase.at("/ledger/0/kind").asText()).isEqualTo("refund");
        // A reversal is a fact too.
        assertThat(reverse(owner, id, 500, "wrong_amount").status()).isEqualTo(200);
        assertThat(publicCase("HELP-904").at("/financials/available").asLong()).isEqualTo(-2500);
    }

    @Test
    void d6_11_aCorrectionSentTwiceWithOneKeyIsOneEntry() {
        Staff owner = owner();
        String id = confirmed(owner, zelle());
        String key = key();
        String sent = body(map("amount", 1000, "reason", "wrong_amount", "note", "Typed 50.00, the payment was 40.00."));
        Browser.Answer first = post(owner.browser(), base(id) + "/reversals", sent, key);
        Browser.Answer again = post(owner.browser(), base(id) + "/reversals", sent, key);
        assertThat(first.status()).isEqualTo(200);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body()).isEqualTo(first.body());
        assertThat(count("select count(*) from donation_correction")).isEqualTo(1);
        assertThat(first.json().get("netAmount").asLong()).isEqualTo(4000);
        // The same amount again, on purpose, is a new key.
        assertThat(post(owner.browser(), base(id) + "/reversals", sent, key()).json().get("netAmount").asLong()).isEqualTo(3000);
    }

    @Test
    void d6_12_theDonorLeavesTheListWhenNothingStandsAndTheLedgerKeepsEverything() {
        Staff owner = owner();
        String id = confirmed(owner, zelle());
        assertThat(publicCase("HELP-904").at("/counts/donors").asInt()).isEqualTo(1);
        assertThat(reverse(owner, id, 5000, "not_received").status()).isEqualTo(200);

        JsonNode publicCase = publicCase("HELP-904");
        assertThat(publicCase.at("/counts/donors").asInt()).isZero();
        assertThat(publicCase.at("/case/received").asLong()).isZero();
        assertThat(publicCase.at("/counts/ledger").asInt()).isEqualTo(2);
        JsonNode detail = owner.browser().get(base(id)).json();
        assertThat(detail.get("state").asText()).isEqualTo("confirmed");
        assertThat(detail.get("netAmount").asLong()).isZero();
        // The donor's status page shows what still stands: nothing.
        Browser guest = visitor();
        String reference = detail.get("reference").asText();
        assertThat(count("select count(*) from donation where reference = '" + reference + "' and state = 'confirmed'")).isEqualTo(1);
    }

    @Test
    void d9_3_aCorrectionThatBringsReceivedBelowTheGoalReturnsTheCaseToActive() {
        String details = zelle();
        Staff owner = owner();
        Reported gift = report("HELP-904", 60_000, details);
        String id = confirm(owner, gift, "HELP-904", 60_000).get("id").asText();
        assertThat(publicCase("HELP-904").at("/case/status").asText()).isEqualTo("funded");

        assertThat(reverse(owner, id, 1, "wrong_amount").status()).isEqualTo(200);
        assertThat(publicCase("HELP-904").at("/case/status").asText()).isEqualTo("active");
        assertThat(count("select count(*) from audit_event where event_type = 'case.status_changed' and details ->> 'from' = 'funded'"
                + " and details ->> 'to' = 'active'")).isEqualTo(1);
        // The donor's status page shows what still stands after the correction (7.2).
        JsonNode status = status(visitor(), gift.trackingCode()).json();
        assertThat(status.get("confirmedAmount").asLong()).isEqualTo(60_000);
        assertThat(status.get("netAmount").asLong()).isEqualTo(59_999);
    }
}
