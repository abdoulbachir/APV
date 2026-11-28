package org.apvgakasinay.donations;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.apvgakasinay.identity.Browser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;

/**
 * Section 11 of contract/tests/donation-acceptance.md. A whole journey (report, screenshot,
 * status, review, corrections, re-entry, refusals) is run with everything that could print a
 * request, a statement or an answer turned up to TRACE, far beyond what production logs. The
 * console, the audit log and every table that is not the gift's own private columns are then
 * searched for everything the contract says is never written (13).
 */
@ExtendWith({org.apvgakasinay.support.RestoreLogLevels.class, OutputCaptureExtension.class})
@TestPropertySource(properties = {
        "logging.level.org.apvgakasinay=TRACE",
        "logging.level.org.springframework.security=TRACE",
        "logging.level.org.springframework.session=TRACE",
        "logging.level.org.springframework.web=TRACE",
        "logging.level.org.springframework.http=TRACE",
        "logging.level.org.springframework.jdbc=TRACE",
        "logging.level.org.springframework.transaction=TRACE",
        "logging.level.org.apache.coyote=TRACE",
        "logging.level.org.apache.tomcat=TRACE",
        "logging.level.org.apache.catalina=TRACE",
        "logging.level.org.postgresql=TRACE",
        "logging.level.com.zaxxer.hikari=TRACE"})
class AuditAndPrivacyIT extends DonationIntegration {

    /** The keys a donation audit record may carry. Nothing else, and none of them is free text a person typed. */
    private static final Set<String> ALLOWED_KEYS = Set.of("gift", "case", "method", "details", "amount", "account_linked", "format", "bytes",
            "reason", "reported_amount", "confirmed_amount", "reported_case", "confirmed_case", "ledger_entry", "listing", "amount_differs",
            "case_differs", "reentry_of", "remaining", "rule", "from", "to", "status", "brake", "fields", "revision", "position", "attested",
            "operation", "permission", "state");

    private static void add(List<String[]> secrets, String kind, String value) {
        secrets.add(new String[] {kind, value});
    }

    @Test
    void d11_1_d11_2_d11_3_nothingPrivateIsWrittenToTheLogOrTheAuditLog(CapturedOutput output) {
        List<String[]> secrets = new ArrayList<>();
        String details = zelle();
        Staff owner = owner();

        // A report with every private field, a public name and an idempotency key, each a word found nowhere else.
        Browser guest = visitor();
        String reportKey = "idem-QUOKKA-" + "k".repeat(20);
        Map<String, Object> report = reportOf("HELP-904", 5000, details);
        report.put("payerName", "Quagga Payername");
        report.put("paymentReference", "ZEBRA-REFERENCE-771");
        report.put("contact", "okapi.contact@example.test");
        report.put("display", map("mode", "named", "publicName", "Tapir Publicname"));
        Reported gift = reported(post(guest, "/api/donations", body(report), reportKey));
        add(secrets, "payer name", "Quagga");
        add(secrets, "payment reference", "ZEBRA-REFERENCE");
        add(secrets, "contact detail", "okapi.contact");
        add(secrets, "public name", "Tapir");
        add(secrets, "idempotency key", "QUOKKA");
        add(secrets, "tracking code", gift.trackingCode());
        add(secrets, "tracking code, secret part", gift.trackingCode().substring(10));
        add(secrets, "tracking code, secret part without dashes", gift.trackingCode().substring(10).replace("-", ""));
        add(secrets, "upload token", gift.uploadToken());
        // Sent again, and with another body: the answers of a repeated and of a reused key.
        post(guest, "/api/donations", body(report), reportKey);
        report.put("amount", 5001);
        post(guest, "/api/donations", body(report), reportKey);
        // Refused reports carry private values too: a card number, and a body that is not JSON.
        Map<String, Object> card = reportOf("HELP-904", 5000, details);
        card.put("payerName", "4111 1111 1111 1111");
        post(visitor(), "/api/donations", body(card), key());
        add(secrets, "card number", "4111 1111 1111 1111");
        add(secrets, "card number", "4111111111111111");
        post(visitor(), "/api/donations", "this is not json but it names Wombat Payer", key());
        add(secrets, "payer name in an unreadable body", "Wombat");

        // A screenshot whose content is recognisable, and a refused one.
        byte[] picture = Pictures.withExif(Pictures.jpeg(300, 200), 1, "NARWHAL-SCREENSHOT-CONTENT");
        guest.extraHeaders.put("X-Evidence-Token", gift.uploadToken());
        assertThat(guest.sendBytes("POST", "/api/donations/evidence", picture, "image/jpeg", guest.token).status()).isEqualTo(204);
        guest.sendBytes("POST", "/api/donations/evidence", "NARWHAL not a picture".getBytes(), "image/png", guest.token);
        guest.extraHeaders.clear();
        add(secrets, "screenshot content", "NARWHAL");
        status(guest, gift.trackingCode());
        status(guest, gift.trackingCode().substring(0, 10) + "0000-0000-0000");

        // Review: the detail and the list are read, the screenshot is read, the gift is confirmed with a note.
        String id = giftId(gift.reference());
        owner.browser().get("/api/admin/donations");
        owner.browser().get("/api/admin/donations/" + id);
        owner.browser().get("/api/admin/donations/" + id + "/evidence");
        String staffKey = "idem-NUMBAT-" + "k".repeat(20);
        Map<String, Object> confirmation = confirmationOf("FUND", 4800);
        confirmation.put("note", "Pangolin note: the donor asked for the fund.");
        confirmation.put("providerReference", "AXOLOTL-PROVIDER-9");
        assertThat(post(owner.browser(), "/api/admin/donations/" + id + "/confirm", body(confirmation), staffKey).status()).isEqualTo(200);
        post(owner.browser(), "/api/admin/donations/" + id + "/confirm", body(confirmation), staffKey);
        add(secrets, "note", "Pangolin");
        add(secrets, "idempotency key", "NUMBAT");

        // Corrections, a refusal by a rule, a restricted listing, a re-entry, a rejection and a reopening.
        post(owner.browser(), "/api/admin/donations/" + id + "/refunds",
                body(map("amount", 800, "refundedOn", TODAY, "providerReference", "AXOLOTL-RETURN-3", "note", "Ocelot note: part returned.")), key());
        post(owner.browser(), "/api/admin/donations/" + id + "/reversals", body(map("amount", 9999, "reason", "other", "note", "Ocelot note: too much.")), key());
        add(secrets, "note", "Ocelot");
        owner.browser().post("/api/admin/donations/" + id + "/listing", body(map("listing", "anonymous")));
        Reported second = reported(post(visitor(), "/api/donations", body(reportOf("HELP-904", 700, details)), key()));
        String secondId = giftId(second.reference());
        owner.browser().post("/api/admin/donations/" + secondId + "/reject", body(map("reason", "other", "note", "Marmot note: cannot be found.")));
        owner.browser().postEmpty("/api/admin/donations/" + secondId + "/reopen");
        add(secrets, "note", "Marmot");
        JsonNode entered = post(owner.browser(), "/api/admin/donations", body(map("caseCode", "FUND", "amount", 900, "receivedOn", TODAY,
                "method", map("description", "Capybara envelope"), "payerName", "Lemur Payername",
                "display", map("mode", "named", "publicName", "Dugong Publicname"), "note", "Tarsier note: handed over.")), key()).json();
        assertThat(entered.get("state").asText()).isEqualTo("confirmed");
        add(secrets, "method description", "Capybara");
        add(secrets, "payer name", "Lemur");
        add(secrets, "public name", "Dugong");
        add(secrets, "note", "Tarsier");
        // An account without the permission, refused.
        post(donor("sample.donor@example.org"), "/api/admin/donations/" + id + "/confirm", body(confirmation), key());

        // 11.2 and 11.3: the console, at TRACE.
        String log = output.getAll();
        assertThat(log).as("the journey was logged in detail").contains("/api/donations").contains("/api/admin/donations").contains("TRACE");
        for (String[] secret : secrets) {
            assertThat(log).as("%s must never be logged", secret[0]).doesNotContain(secret[1]);
        }
        // The audit log, and the tables that remember requests and count them.
        for (String table : List.of("audit_event", "idempotency_record", "donation_throttle", "donation_history", "donation_evidence")) {
            for (String[] secret : secrets) {
                assertThat(count("select count(*) from " + table + " t where position('" + secret[1].replace("'", "''") + "' in row(t.*)::text) > 0"))
                        .as("%s in %s", secret[0], table).isZero();
            }
        }

        // 11.1: every event of the journey is there once, with details from the allowlist only.
        for (String event : List.of("donation.reported", "donation.evidence_attached", "donation.evidence_refused", "donation.evidence_read",
                "donation.confirmed", "donation.refunded", "donation.decision_refused", "donation.listing_restricted", "donation.rejected",
                "donation.reopened", "donation.entered", "staff.permission_refused", "payment_method.created",
                "payment_method.details_published", "payment_method.activated")) {
            assertThat(count("select count(*) from audit_event where event_type = '" + event + "'")).as(event).isPositive();
        }
        Set<String> keys = new TreeSet<>(jdbc.sql("""
                select distinct k from audit_event, jsonb_object_keys(details) as k
                 where event_type like 'donation.%' or event_type like 'case.%' or event_type like 'payment_method.%' or event_type like 'staff.%'
                """).query(String.class).list());
        assertThat(ALLOWED_KEYS).containsAll(keys);
        // Each record of a staff action names the actor, the address and the request; a guest's names no account.
        assertThat(count("select count(*) from audit_event where event_type = 'donation.confirmed' and actor_account_id is not null"
                + " and client_address is not null and request_id is not null and outcome = 'success' and details ->> 'gift' = '" + id + "'"
                + " and details ->> 'reported_amount' = '5000' and details ->> 'confirmed_amount' = '4800' and details ->> 'reported_case' = 'HELP-904'"
                + " and details ->> 'confirmed_case' = 'FUND' and details ->> 'case_differs' = 'true' and details ->> 'amount_differs' = 'true'"
                + " and details ->> 'listing' = 'named'")).isEqualTo(1);
        assertThat(count("select count(*) from audit_event where event_type = 'donation.reported' and actor_account_id is null"
                + " and details ->> 'account_linked' = 'false' and details ->> 'method' = 'zelle' and details ->> 'details' = '" + details + "'")).isEqualTo(2);
        // The gift's own history holds nothing the audit log does not: an action, an instant, an actor.
        assertThat(jdbc.sql("select string_agg(action, ',' order by at, id) from donation_history where donation_id = (select id from donation where public_id = :id)")
                .param("id", id).query(String.class).single()).isEqualTo("reported,evidence_attached,confirmed,refunded,listing_restricted");
    }
}
