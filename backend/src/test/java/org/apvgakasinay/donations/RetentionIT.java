package org.apvgakasinay.donations;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.apvgakasinay.donations.internal.DonationMaintenance;
import org.apvgakasinay.identity.Browser;
import org.apvgakasinay.support.SharedPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Section 12 of contract/tests/donation-acceptance.md: the retention periods the owner approved (decision 15). */
class RetentionIT extends DonationIntegration {

    @Autowired
    private DonationMaintenance maintenance;

    private Reported private_(String details, long amount) {
        Map<String, Object> report = reportOf("HELP-904", amount, details);
        report.put("payerName", "Quagga Payer");
        report.put("paymentReference", "ZEBRA-REF-1");
        report.put("contact", "okapi@example.test");
        report.put("display", map("mode", "named", "publicName", "Tapir Public"));
        return reported(post(visitor(), "/api/donations", body(report), key()));
    }

    private void attach(Reported gift) {
        Browser browser = visitor();
        browser.extraHeaders.put("X-Evidence-Token", gift.uploadToken());
        assertThat(browser.sendBytes("POST", "/api/donations/evidence", Pictures.png(120, 120), "image/png", browser.token).status()).isEqualTo(204);
    }

    private Path storedFile(Reported gift) {
        String key = jdbc.sql("select v.storage_key from donation_evidence v join donation d on d.id = v.donation_id where d.reference = :reference")
                .param("reference", gift.reference()).query(String.class).single();
        return SharedPostgres.evidenceRoot().resolve(key);
    }

    private String today() {
        return java.time.LocalDate.ofInstant(clock.now(), java.time.ZoneId.of("America/Chicago")).toString();
    }

    @Test
    void d12_1_d12_5_aScreenshotIsDeletedThirtyDaysAfterTheDecisionAndNeverWhileTheGiftWaits() throws Exception {
        String details = zelle();
        Reported waiting = private_(details, 1000);
        Reported rejected = private_(details, 2000);
        Reported confirmed = private_(details, 3000);
        attach(waiting);
        attach(rejected);
        attach(confirmed);
        Staff owner = owner();
        owner.browser().post("/api/admin/donations/" + giftId(rejected.reference()) + "/reject", body(map("reason", "not_received")));
        confirm(owner, confirmed, "HELP-904", 3000);

        advance(Duration.ofDays(30).minusMinutes(5));
        assertThat(maintenance.applyRetention()).isZero();
        assertThat(storedFile(rejected)).exists();

        advance(Duration.ofMinutes(10));
        assertThat(maintenance.applyRetention()).isEqualTo(2);
        assertThat(storedFile(rejected)).doesNotExist();
        assertThat(storedFile(confirmed)).doesNotExist();
        // While a gift is reported its screenshot is kept, however long that lasts.
        assertThat(storedFile(waiting)).exists();
        advance(Duration.ofDays(400));
        // By now the rejected gift's private details are due; no screenshot is.
        assertThat(maintenance.applyRetention()).isEqualTo(1);
        assertThat(Files.size(storedFile(waiting))).isPositive();
        assertThat(count("select count(*) from donation_evidence where deleted_at is null")).isEqualTo(1);

        // The gift keeps that one existed and when it was deleted; the task, not a person, is the actor.
        Browser again = signedInAgain(owner);
        JsonNode detail = again.get("/api/admin/donations/" + giftId(rejected.reference())).json();
        assertThat(detail.get("evidence").asText()).isEqualTo("deleted");
        assertThat(detail.at("/history/3/action").asText()).isEqualTo("evidence_deleted");
        assertThat(detail.at("/history/3/by").isNull()).isTrue();
        assertThat(count("select count(*) from donation_evidence where deleted_at is not null")).isEqualTo(2);
        assertError(again.get("/api/admin/donations/" + giftId(rejected.reference()) + "/evidence"), 404, "evidence_not_found");
        assertThat(count("select count(*) from audit_event where event_type = 'donation.evidence_deleted' and actor_operator = 'retention-task'"
                + " and actor_account_id is null and details ->> 'reason' = 'retention'")).isEqualTo(2);
    }

    @Test
    void d12_2_d12_3_privateDetailsAreErasedAfterTheirPeriodAndTheFinancialRecordStays() {
        String details = zelle();
        Reported rejected = private_(details, 2000);
        Reported confirmed = private_(details, 3000);
        Staff owner = owner();
        String rejectedId = giftId(rejected.reference());
        String confirmedId = giftId(confirmed.reference());
        owner.browser().post("/api/admin/donations/" + rejectedId + "/reject", body(map("reason", "other", "note", "Marmot note.")));
        Map<String, Object> confirmation = confirmationOf("HELP-904", 3000);
        confirmation.put("note", "Pangolin note.");
        confirmation.put("providerReference", "AXOLOTL-9");
        post(owner.browser(), "/api/admin/donations/" + confirmedId + "/confirm", body(confirmation), key());
        post(owner.browser(), "/api/admin/donations/" + confirmedId + "/reversals",
                body(map("amount", 500, "reason", "wrong_amount", "note", "Ocelot note.")), key());
        String entry = post(owner.browser(), "/api/admin/donations", body(map("caseCode", "FUND", "amount", 900, "receivedOn", TODAY,
                "method", map("description", "Cash, in person"), "payerName", "Lemur Payer", "display", map("mode", "anonymous"),
                "note", "Tarsier note.")), key()).json().get("id").asText();
        long received = publicCase("HELP-904").at("/case/received").asLong();
        long audits = count("select count(*) from audit_event");

        // Ninety days after a rejection.
        advance(Duration.ofDays(90).minusMinutes(5));
        assertThat(maintenance.applyRetention()).isZero();
        advance(Duration.ofMinutes(10));
        assertThat(maintenance.applyRetention()).isEqualTo(1);
        Browser again = signedInAgain(owner);
        JsonNode erased = again.get("/api/admin/donations/" + rejectedId).json();
        assertValid("DonationDetail", again.get("/api/admin/donations/" + rejectedId));
        assertThat(erased.get("payerName").isNull()).isTrue();
        assertThat(erased.get("paymentReference").isNull()).isTrue();
        assertThat(erased.get("contact").isNull()).isTrue();
        assertThat(erased.at("/decision/note").isNull()).isTrue();
        assertThat(erased.get("privateDetailsErasedAt").asText()).isNotBlank();
        // What is not private is the record: the amount, the case, the dates, the method, the state, the decision.
        assertThat(erased.at("/reported/amount").asLong()).isEqualTo(2000);
        assertThat(erased.at("/reported/caseCode").asText()).isEqualTo("HELP-904");
        assertThat(erased.at("/method/id").asText()).isEqualTo("zelle");
        assertThat(erased.at("/decision/rejectionReason").asText()).isEqualTo("other");
        assertThat(erased.get("history")).hasSize(2);
        // The confirmed gift is untouched: its period is twenty-four months.
        assertThat(again.get("/api/admin/donations/" + confirmedId).json().get("payerName").asText()).isEqualTo("Quagga Payer");

        // 12.3: the tracking code still works; the status never showed anything private.
        JsonNode status = status(visitor(), rejected.trackingCode()).json();
        assertThat(status.get("state").asText()).isEqualTo("rejected");
        assertThat(status.get("reportedAmount").asLong()).isEqualTo(2000);

        // Twenty-four months after a confirmation, counted in calendar months.
        advance(Duration.ofDays(639));
        assertThat(maintenance.applyRetention()).isZero();
        advance(Duration.ofDays(3));
        assertThat(maintenance.applyRetention()).isEqualTo(2);
        Browser later = signedInAgain(owner);
        JsonNode kept = later.get("/api/admin/donations/" + confirmedId).json();
        assertThat(kept.get("payerName").isNull()).isTrue();
        assertThat(kept.get("contact").isNull()).isTrue();
        assertThat(kept.at("/decision/note").isNull()).isTrue();
        // A public name the donor agreed to show, the provider's reference and every amount are kept for ever.
        assertThat(kept.at("/display/publicName").asText()).isEqualTo("Tapir Public");
        assertThat(kept.at("/decision/providerReference").asText()).isEqualTo("AXOLOTL-9");
        assertThat(kept.at("/decision/amount").asLong()).isEqualTo(3000);
        assertThat(kept.get("netAmount").asLong()).isEqualTo(2500);
        assertThat(kept.at("/corrections/0/reason").asText()).isEqualTo("wrong_amount");
        assertThat(count("select count(*) from donation_correction where note is not null")).isZero();
        JsonNode enteredAfter = later.get("/api/admin/donations/" + entry).json();
        assertThat(enteredAfter.get("payerName").isNull()).isTrue();
        assertThat(enteredAfter.at("/decision/note").isNull()).isTrue();
        assertThat(enteredAfter.at("/method/description").asText()).isEqualTo("Cash, in person");
        assertThat(publicCase("HELP-904").at("/case/received").asLong()).isEqualTo(received);
        assertThat(publicCase("HELP-904").at("/donors/0/name").asText()).isEqualTo("Tapir Public");
        // A correction is still possible after erasure: the reviewer relies on the provider's records.
        assertThat(post(later, "/api/admin/donations/" + confirmedId + "/refunds",
                body(map("amount", 100, "refundedOn", today(), "note", "Returned later.")), key()).status()).isEqualTo(200);

        // 12.5: one audit record for each erasure, naming which fields held something; nothing was removed from the audit log.
        assertThat(count("select count(*) from audit_event where event_type = 'donation.private_details_erased' and actor_operator = 'retention-task'")).isEqualTo(3);
        assertThat(count("select count(*) from audit_event where event_type = 'donation.private_details_erased' and details ->> 'gift' = '" + confirmedId
                + "' and details ->> 'fields' = 'payer_name,payment_reference,contact,note,correction_notes'")).isEqualTo(1);
        assertThat(count("select count(*) from audit_event")).isGreaterThan(audits);
        assertThat(count("select count(*) from audit_event where details::text like '%Quagga%' or details::text like '%Pangolin%'")).isZero();
        // An erased gift is erased once.
        assertThat(maintenance.applyRetention()).isZero();
    }

    @Test
    void d12_4_reopeningARejectedGiftRestartsItsPeriod() {
        String details = zelle();
        Reported gift = private_(details, 2000);
        String id = giftId(gift.reference());
        Staff owner = owner();
        owner.browser().post("/api/admin/donations/" + id + "/reject", body(map("reason", "not_received")));

        advance(Duration.ofDays(80));
        Browser again = signedInAgain(owner);
        assertThat(again.postEmpty("/api/admin/donations/" + id + "/reopen").status()).isEqualTo(200);
        advance(Duration.ofDays(200));
        // Reported again: nothing is erased while it waits, whatever was true before.
        assertThat(maintenance.applyRetention()).isZero();
        Browser later = signedInAgain(owner);
        assertThat(later.get("/api/admin/donations/" + id).json().get("payerName").asText()).isEqualTo("Quagga Payer");

        later.post("/api/admin/donations/" + id + "/reject", body(map("reason", "not_received")));
        advance(Duration.ofDays(89));
        assertThat(maintenance.applyRetention()).isZero();
        advance(Duration.ofDays(2));
        assertThat(maintenance.applyRetention()).isEqualTo(1);
        assertThat(signedInAgain(owner).get("/api/admin/donations/" + id).json().get("payerName").isNull()).isTrue();
    }

    @Test
    void d4_19_aGiftWhoseNameHasBeenErasedNoLongerSurfacesAsAPossibleDuplicate() {
        String details = zelle();
        Reported first = private_(details, 5000);
        Reported second = private_(details, 5000);
        Staff owner = owner();
        assertThat(owner.browser().get("/api/admin/donations/" + giftId(second.reference())).json().get("possibleDuplicates")).hasSize(1);
        owner.browser().post("/api/admin/donations/" + giftId(first.reference()) + "/reject", body(map("reason", "duplicate")));

        // The match is on the payer name, which retention erases: the hint degrades with it, by design.
        advance(Duration.ofDays(91));
        assertThat(maintenance.applyRetention()).isEqualTo(1);
        JsonNode detail = signedInAgain(owner).get("/api/admin/donations/" + giftId(second.reference())).json();
        assertThat(detail.get("possibleDuplicates")).isEmpty();
        assertThat(detail.get("flags").toString()).doesNotContain("possible_duplicate");
    }

    @Test
    void housekeepingRemovesCountersAndKeysThatCanNoLongerMatter() {
        String details = zelle();
        Reported gift = private_(details, 5000);
        assertThat(count("select count(*) from idempotency_record")).isEqualTo(1);
        assertThat(count("select count(*) from donation_throttle")).isPositive();

        advance(Duration.ofHours(23));
        maintenance.removeExpired();
        // The counters' windows are long over; the report's key is remembered for a day.
        assertThat(count("select count(*) from donation_throttle")).isZero();
        assertThat(count("select count(*) from idempotency_record")).isEqualTo(1);
        advance(Duration.ofHours(2));
        maintenance.removeExpired();
        assertThat(count("select count(*) from idempotency_record")).isZero();
        // The gift itself is never removed.
        assertThat(count("select count(*) from donation where reference = '" + gift.reference() + "'")).isEqualTo(1);
    }
}
