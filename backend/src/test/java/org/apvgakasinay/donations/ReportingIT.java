package org.apvgakasinay.donations;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apvgakasinay.identity.Browser;
import org.junit.jupiter.api.Test;

/** Section 2 of contract/tests/donation-acceptance.md: reporting a gift. */
class ReportingIT extends DonationIntegration {

    private static final List<String> PUBLIC_READS = List.of("/api/site", "/api/stats", "/api/cases", "/api/cases/HELP-904",
            "/api/cases/HELP-904/updates", "/api/cases/HELP-904/donors", "/api/cases/HELP-904/ledger", "/api/cases/FUND");

    private Browser guest() {
        Browser browser = browser();
        browser.csrf();
        return browser;
    }

    private Browser.Answer send(Map<String, Object> report) {
        return post(guest(), "/api/donations", body(report), key());
    }

    private void assertField(Browser.Answer answer, String field, String code) {
        assertError(answer, 400, "validation_error");
        assertThat(answer.json().at("/error/fields/0/name").asText()).as(answer.body()).isEqualTo(field);
        assertThat(answer.fieldCode()).as(answer.body()).isEqualTo(code);
    }

    private List<String> publicReads() {
        List<String> bodies = new ArrayList<>();
        for (String path : PUBLIC_READS) {
            Browser.Answer answer = browser().get(path);
            assertThat(answer.status()).as(path).isEqualTo(200);
            bodies.add(answer.body());
        }
        return bodies;
    }

    @Test
    void d2_1_aGuestReportsAGiftAndReceivesTheReceiptWhileNothingPublicChanges() {
        String details = zelle();
        List<String> before = publicReads();
        long entriesBefore = count("select count(*) from ledger_entry");

        Map<String, Object> report = reportOf("help-904", 5000, details);
        report.put("paymentReference", "ZL-48213907");
        report.put("contact", "donor@example.test");
        report.put("display", map("mode", "named", "publicName", "Sample D."));
        Browser.Answer answer = send(report);
        Reported gift = reported(answer);

        assertThat(answer.header("Cache-Control")).isEqualTo("no-store");
        assertThat(gift.receipt().get("state").asText()).isEqualTo("reported");
        // The canonical upper-case code, whatever case was sent.
        assertThat(gift.receipt().get("caseCode").asText()).isEqualTo("HELP-904");
        assertThat(gift.receipt().get("amount").asLong()).isEqualTo(5000);
        assertThat(gift.receipt().get("reportedAt").asText()).isEqualTo("2026-11-24T12:00:00Z");
        assertThat(gift.trackingCode()).startsWith(gift.reference() + "-").hasSize(24);
        assertThat(gift.uploadToken()).hasSize(43);
        assertThat(gift.receipt().at("/evidenceUpload/expiresAt").asText()).isEqualTo("2026-11-24T12:30:00Z");
        assertThat(gift.receipt().at("/evidenceUpload/maxBytes").asInt()).isEqualTo(5242880);

        assertThat(count("select count(*) from donation where state = 'reported' and origin = 'reported' and account_id is null")).isEqualTo(1);
        assertThat(count("select count(*) from ledger_entry")).isEqualTo(entriesBefore);
        assertThat(count("select count(*) from donation_listing")).isEqualTo(count("select count(*) from donation_listing where public_id like 'D%'"));
        assertThat(publicReads()).isEqualTo(before);
        // Neither secret is stored: the database holds what they are computed from, and no more.
        String secretPart = gift.trackingCode().substring(10).replace("-", "");
        assertThat(count("select count(*) from donation where position('" + secretPart + "' in row(donation.*)::text) > 0")).isZero();
        assertThat(count("select count(*) from donation where position('" + gift.uploadToken() + "' in row(donation.*)::text) > 0")).isZero();
        assertThat(count("select count(*) from idempotency_record where response_json like '%" + gift.reference() + "%'")).isZero();
    }

    @Test
    void d2_2_aGiftCanBeReportedToTheGeneralFund() {
        Reported gift = report("FUND", 2500, zelle());
        assertThat(gift.receipt().get("caseCode").asText()).isEqualTo("FUND");
    }

    @Test
    void d2_3_d2_19_aReportWithoutATokenOrFromAnotherSiteRecordsNothing() {
        String details = zelle();
        Browser noToken = guest();
        assertError(noToken.post("/api/donations", body(reportOf("HELP-904", 5000, details)), null), 403, "csrf_invalid");
        // No session at all: refused the same way, and no session is created by the refusal.
        Browser.Answer noSession = browser().post("/api/donations", body(reportOf("HELP-904", 5000, details)), "anything");
        assertError(noSession, 403, "csrf_invalid");
        assertThat(noSession.headers().allValues("Set-Cookie")).isEmpty();

        Browser elsewhere = guest();
        elsewhere.extraHeaders.put("Origin", "https://attacker.example");
        assertError(post(elsewhere, "/api/donations", body(reportOf("HELP-904", 5000, details)), key()), 403, "forbidden");
        Browser crossSite = guest();
        crossSite.extraHeaders.put("Sec-Fetch-Site", "cross-site");
        assertError(post(crossSite, "/api/donations", body(reportOf("HELP-904", 5000, details)), key()), 403, "forbidden");

        assertThat(count("select count(*) from donation")).isZero();
        assertThat(count("select count(*) from idempotency_record")).isZero();
    }

    @Test
    void d2_4_anAmountOutsideOneMovementIsRefused() {
        String details = zelle();
        assertField(send(reportOf("HELP-904", 0, details)), "amount", "out_of_range");
        assertField(send(reportOf("HELP-904", -5, details)), "amount", "out_of_range");
        assertField(send(reportOf("HELP-904", 10_000_000_001L, details)), "amount", "out_of_range");
        Map<String, Object> fraction = reportOf("HELP-904", 1, details);
        fraction.put("amount", 12.5);
        assertField(send(fraction), "amount", "invalid_type");
        fraction.put("amount", "5000");
        assertField(send(fraction), "amount", "invalid_type");
        reported(send(reportOf("HELP-904", 10_000_000_000L, details)));
        assertThat(count("select count(*) from donation")).isEqualTo(1);
    }

    @Test
    void d2_5_anUnknownCaseAndADraftAreAnsweredAlike() {
        String details = zelle();
        Browser.Answer unknown = send(reportOf("ZZZ-999", 5000, details));
        Browser.Answer draft = send(reportOf("HELP-902", 5000, details));
        assertField(unknown, "caseCode", "invalid_value");
        assertField(draft, "caseCode", "invalid_value");
        assertThat(draft.body().replaceAll("\"requestId\":\"[0-9a-f]+\"", "")).isEqualTo(unknown.body().replaceAll("\"requestId\":\"[0-9a-f]+\"", ""));
        assertField(send(reportOf("not a code", 5000, details)), "caseCode", "invalid_format");
        assertThat(count("select count(*) from donation")).isZero();
    }

    @Test
    void d2_6_aCaseThatDoesNotAcceptGiftsNowIsRefused() {
        String details = zelle();
        // Delivered: a real, public case that no longer takes gifts.
        assertError(send(reportOf("HELP-903", 5000, details)), 409, "case_not_accepting");
        // Paused by its administrator.
        jdbc.sql("update aid_case set accepts_donations = false where code = 'HELP-904'").update();
        assertError(send(reportOf("HELP-904", 5000, details)), 409, "case_not_accepting");
        assertThat(count("select count(*) from donation")).isZero();
        assertThat(count("select count(*) from idempotency_record")).isZero();
    }

    @Test
    void d2_7_d2_8_detailsReplacedLessThanADayAgoAreAcceptedAndOlderOnesAreNot() {
        String first = zelle();
        paymentMethods.publishDetails("zelle", sampleDetails("new-handle@example.test"), "test-operator");

        advance(Duration.ofHours(23).plusMinutes(59));
        Reported gift = reported(send(reportWithToday(first)));
        // The review screen says the details the donor saw have been superseded.
        Staff owner = owner();
        JsonNode detail = owner.browser().get("/api/admin/donations/" + giftId(gift.reference())).json();
        assertThat(detail.at("/shownDetails/detailsId").asText()).isEqualTo(first);
        assertThat(detail.at("/shownDetails/current").asBoolean()).isFalse();
        assertThat(detail.at("/shownDetails/supersededAt").asText()).isEqualTo("2026-11-24T12:00:00Z");
        assertThat(detail.at("/shownDetails/recipient/0/value").asText()).isEqualTo("gifts+zelle@example.test");
        assertThat(detail.get("flags").toString()).contains("superseded_details");

        advance(Duration.ofMinutes(1));
        assertError(send(reportWithToday(first)), 409, "payment_details_changed");
        // The current details still work, and an identifier that names nothing is a validation error.
        reported(send(reportWithToday(currentDetails("zelle"))));
        assertField(send(reportWithToday("pmd_AAAAAAAAAAAAAAAAAAAAAA")), "paymentDetailsId", "invalid_value");
        assertField(send(reportWithToday("not-an-id")), "paymentDetailsId", "invalid_format");
        assertThat(count("select count(*) from donation")).isEqualTo(2);
    }

    @Test
    void d2_8_theDetailsOfAMethodDeactivatedADayAgoAreNoLongerAccepted() {
        String details = zelle();
        paymentMethods.deactivate("zelle", "test-operator");
        advance(Duration.ofHours(2));
        reported(send(reportWithToday(details)));
        advance(Duration.ofHours(22));
        assertError(send(reportWithToday(details)), 409, "payment_details_changed");
    }

    /** A report whose day paid is today on the clock the test has moved to. */
    private Map<String, Object> reportWithToday(String details) {
        Map<String, Object> report = reportOf("HELP-904", 5000, details);
        report.put("paidOn", java.time.LocalDate.ofInstant(clock.now(), java.time.ZoneId.of("America/Chicago")).toString());
        return report;
    }

    @Test
    void d2_9_theBodyIsSmallJsonAndClosed() {
        String details = zelle();
        Browser guest = guest();
        Map<String, Object> large = reportOf("HELP-904", 5000, details);
        large.put("contact", "x".repeat(17 * 1024));
        assertError(post(guest, "/api/donations", body(large), key()), 413, "payload_too_large");

        guest.extraHeaders.put("Idempotency-Key", key());
        assertError(guest.send("POST", "/api/donations", body(reportOf("HELP-904", 5000, details)), "text/plain", guest.token), 415, "unsupported_media_type");
        guest.extraHeaders.clear();

        // A report cannot carry a state, a confirmed amount, an account, a permission or an identifier of its own.
        for (String smuggled : List.of("state", "confirmedAmount", "accountId", "permissions", "id", "reference", "listing")) {
            Map<String, Object> report = reportOf("HELP-904", 5000, details);
            report.put(smuggled, "confirmed");
            assertField(send(report), smuggled, "unknown_field");
        }
        Map<String, Object> nested = reportOf("HELP-904", 5000, details);
        nested.put("display", map("mode", "anonymous", "avatar", "x"));
        assertField(send(nested), "display.avatar", "unknown_field");
        assertField(post(guest(), "/api/donations", "[1,2]", key()), "body", "invalid_type");
        assertField(post(guest(), "/api/donations", "this is not json", key()), "body", "invalid_type");
        assertThat(count("select count(*) from donation")).isZero();
    }

    @Test
    void d2_10_aPaymentCardNumberInAnyTextFieldIsRefusedAndNeverStored() {
        String details = zelle();
        // The standard test number of the card networks: 16 digits with a valid check digit.
        for (String card : List.of("4111111111111111", "4111 1111 1111 1111", "4111-1111-1111-1111", "378282246310005")) {
            for (String field : List.of("payerName", "paymentReference", "contact")) {
                Map<String, Object> report = reportOf("HELP-904", 5000, details);
                report.put(field, card);
                Browser.Answer answer = send(report);
                assertField(answer, field, "card_number");
                assertThat(answer.body()).doesNotContain(card);
            }
        }
        Map<String, Object> named = reportOf("HELP-904", 5000, details);
        named.put("display", map("mode", "named", "publicName", "4111 1111 1111 1111"));
        assertField(send(named), "display.publicName", "card_number");
        assertThat(count("select count(*) from donation")).isZero();
        assertThat(count("select count(*) from audit_event where details::text like '%4111%'")).isZero();

        // Sixteen digits that are not a card number, and a short reference, are what they look like: references.
        Map<String, Object> report = reportOf("HELP-904", 5000, details);
        report.put("paymentReference", "4111111111111112");
        reported(send(report));
    }

    @Test
    void d2_11_textIsNotTrimmedByTheServerAndHoldsNoControlCharacter() {
        String details = zelle();
        for (String bad : List.of(" Sample Donor", "Sample Donor ", "Sample\tDonor", "Sample\nDonor", "Sample\u0000Donor", "Sample Donor ",
                // Not NFC: e followed by a combining acute accent.
                "René Donor")) {
            Map<String, Object> report = reportOf("HELP-904", 5000, details);
            report.put("payerName", bad);
            assertField(send(report), "payerName", "invalid_format");
        }
        Map<String, Object> report = reportOf("HELP-904", 5000, details);
        report.put("contact", " donor@example.test");
        assertField(send(report), "contact", "invalid_format");
        report.put("contact", "donor@example.test");
        report.put("payerName", "S");
        assertField(send(report), "payerName", "too_short");
        report.put("payerName", "S".repeat(81));
        assertField(send(report), "payerName", "too_long");
        report.remove("payerName");
        assertField(send(report), "payerName", "required");
        report.put("payerName", 12);
        assertField(send(report), "payerName", "invalid_type");
        // Names in every script are names: accents in NFC, and characters outside the basic plane, counted as one each.
        report.put("payerName", "René Hawaïdou 🌻");
        reported(send(report));
    }

    @Test
    void d2_12_theDayPaidIsWithinNinetyDaysAndNotAfterTomorrow() {
        String details = zelle();
        Map<String, Object> report = reportOf("HELP-904", 5000, details);
        // Today is 2026-11-24 in the organization timezone.
        report.put("paidOn", "2026-08-25");
        assertField(send(report), "paidOn", "out_of_range");
        report.put("paidOn", "2026-11-26");
        assertField(send(report), "paidOn", "out_of_range");
        report.put("paidOn", "24/11/2026");
        assertField(send(report), "paidOn", "invalid_format");
        report.put("paidOn", "2026-02-30");
        assertField(send(report), "paidOn", "invalid_format");
        report.remove("paidOn");
        assertField(send(report), "paidOn", "required");
        // Ninety days ago, and tomorrow for a donor east of the organization.
        report.put("paidOn", "2026-08-26");
        reported(send(report));
        report.put("paidOn", "2026-11-25");
        reported(send(report));
    }

    @Test
    void d2_13_d2_14_aGiftIsLinkedToAnAccountOnlyByAFullySignedInSession() {
        String details = zelle();
        Browser donor = donor("sample.donor@example.org");
        Reported linked = reported(post(donor, "/api/donations", body(reportOf("HELP-904", 5000, details)), key()));
        long donorId = count("select id from account where email_normalized = 'sample.donor@example.org'");
        assertThat(count("select account_id from donation where reference = '" + linked.reference() + "'")).isEqualTo(donorId);

        // A guest who types the same name, and gives that account's address as contact, is still a guest.
        Map<String, Object> lookalike = reportOf("HELP-904", 5000, details);
        lookalike.put("payerName", "Sample Person");
        lookalike.put("contact", "sample.donor@example.org");
        Reported guest = reported(send(lookalike));
        assertThat(count("select count(*) from donation where reference = '" + guest.reference() + "' and account_id is null")).isEqualTo(1);

        // Which account is never shown, only that there is one, and only to the reviewer.
        Staff owner = owner();
        JsonNode linkedDetail = owner.browser().get("/api/admin/donations/" + giftId(linked.reference())).json();
        assertThat(linkedDetail.get("linkedAccount").asBoolean()).isTrue();
        assertThat(linkedDetail.toString()).doesNotContain("sample.donor@example.org");
        assertThat(owner.browser().get("/api/admin/donations/" + giftId(guest.reference())).json().get("linkedAccount").asBoolean()).isFalse();
        // No operation lets one account find another's gift: the review operations are refused to a donor.
        assertError(donor.get("/api/admin/donations"), 403, "forbidden");
    }

    @Test
    void d2_15_theSameKeyAndBodyAgainReturnTheFirstAnswerAndRecordOneGift() {
        String details = zelle();
        Browser guest = guest();
        String key = key();
        String sent = body(reportOf("HELP-904", 5000, details));
        Browser.Answer first = post(guest, "/api/donations", sent, key);
        advance(Duration.ofMinutes(40));
        Browser.Answer again = post(guest, "/api/donations", sent, key);
        // From another browser too: a lost answer is asked for again by whoever holds the key and the body.
        Browser.Answer elsewhere = post(guest(), "/api/donations", sent, key);

        reported(first);
        assertThat(again.status()).isEqualTo(201);
        assertThat(again.body()).isEqualTo(first.body());
        assertThat(elsewhere.body()).isEqualTo(first.body());
        assertThat(count("select count(*) from donation")).isEqualTo(1);
        assertThat(count("select count(*) from donation_history")).isEqualTo(1);
        assertThat(count("select count(*) from audit_event where event_type = 'donation.reported'")).isEqualTo(1);
    }

    @Test
    void d2_16_theSameKeyWithAnotherBodyIsRefusedAndAKeyIsForgottenAfterADay() {
        String details = zelle();
        Browser guest = guest();
        String key = key();
        reported(post(guest, "/api/donations", body(reportOf("HELP-904", 5000, details)), key));
        assertError(post(guest, "/api/donations", body(reportOf("HELP-904", 5001, details)), key), 409, "idempotency_key_reused");
        assertThat(count("select count(*) from donation")).isEqualTo(1);

        // The key is required and well formed, and is never stored as sent.
        Browser other = guest();
        assertField(post(other, "/api/donations", body(reportOf("HELP-904", 5000, details)), null), "Idempotency-Key", "required");
        assertField(post(other, "/api/donations", body(reportOf("HELP-904", 5000, details)), "too-short"), "Idempotency-Key", "invalid_format");
        assertThat(count("select count(*) from idempotency_record where position('" + key + "' in row(idempotency_record.*)::text) > 0")).isZero();

        // A key not seen in the last 24 hours is a new key.
        advance(Duration.ofHours(24).plusSeconds(1));
        Map<String, Object> later = reportOf("HELP-904", 5001, details);
        later.put("paidOn", "2026-11-25");
        reported(post(guest(), "/api/donations", body(later), key));
        assertThat(count("select count(*) from donation")).isEqualTo(2);
    }

    @Test
    void d2_17_reportedAndRejectedGiftsChangeNoPublicResponseByteForByte() {
        String details = zelle();
        List<String> before = publicReads();

        // Twenty, each from its own address: the site takes at most sixty reports an hour (12.3).
        List<Reported> gifts = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Map<String, Object> report = reportOf(i % 2 == 0 ? "HELP-904" : "FUND", 1000 + i, details);
            report.put("display", map("mode", "named", "publicName", "Loud Name " + i));
            gifts.add(reported(send(report)));
        }
        assertThat(publicReads()).isEqualTo(before);

        Staff owner = owner();
        for (Reported gift : gifts.subList(0, 5)) {
            assertThat(owner.browser().post("/api/admin/donations/" + giftId(gift.reference()) + "/reject", body(map("reason", "not_received"))).status())
                    .isEqualTo(200);
        }
        List<String> after = publicReads();
        assertThat(after).isEqualTo(before);
        // No public response carries a count of pending gifts, a reference or a name.
        assertThat(String.join("", after)).doesNotContain("pending").doesNotContain("Loud Name").doesNotContain(gifts.get(0).reference());
    }

    @Test
    void d2_18_aNamedGiftNeedsTheNameTheDonorAgreedToShow() {
        String details = zelle();
        Map<String, Object> report = reportOf("HELP-904", 5000, details);
        report.put("display", map("mode", "named"));
        assertField(send(report), "display.publicName", "required");
        report.put("display", map("mode", "named", "publicName", "S"));
        assertField(send(report), "display.publicName", "too_short");
        report.put("display", map("mode", "public"));
        assertField(send(report), "display.mode", "invalid_value");
        // There is no default on the server.
        report.remove("display");
        assertField(send(report), "display", "required");
        report.put("display", map("publicName", "Sample D."));
        assertField(send(report), "display.mode", "required");
        assertThat(count("select count(*) from donation")).isZero();

        // A name sent with a gift that is not named is not kept.
        report.put("display", map("mode", "anonymous", "publicName", "Sample D."));
        Reported gift = reported(send(report));
        assertThat(count("select count(*) from donation where reference = '" + gift.reference() + "' and public_name is null")).isEqualTo(1);
    }
}
