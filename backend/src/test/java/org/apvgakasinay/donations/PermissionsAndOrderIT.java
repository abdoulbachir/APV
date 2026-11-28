package org.apvgakasinay.donations;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.apvgakasinay.identity.Browser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Section 8 of contract/tests/donation-acceptance.md: permissions, the order of checks, and headers. */
class PermissionsAndOrderIT extends DonationIntegration {

    @Autowired
    private DataSource dataSource;

    private static final String UNKNOWN = "don_AAAAAAAAAAAAAAAAAAAAAA";

    /** Every review operation, for a gift identifier: the method, the path, and a body when it takes one. */
    private static List<String[]> operations(String id) {
        String base = "/api/admin/donations";
        List<String[]> all = new ArrayList<>();
        all.add(new String[] {"GET", base, null});
        all.add(new String[] {"POST", base, body(map("caseCode", "FUND", "amount", 100, "receivedOn", TODAY, "method", map("description", "Cash"),
                "payerName", "Sample Donor", "display", map("mode", "hidden"), "note", "Cash.", "reentryOf", id))});
        all.add(new String[] {"GET", base + "/" + id, null});
        all.add(new String[] {"GET", base + "/" + id + "/evidence", null});
        all.add(new String[] {"POST", base + "/" + id + "/evidence/delete", null});
        all.add(new String[] {"POST", base + "/" + id + "/confirm", body(confirmationOf("HELP-904", 100))});
        all.add(new String[] {"POST", base + "/" + id + "/reject", body(map("reason", "duplicate"))});
        all.add(new String[] {"POST", base + "/" + id + "/reopen", null});
        all.add(new String[] {"POST", base + "/" + id + "/reversals", body(map("amount", 1, "reason", "other", "note", "x"))});
        all.add(new String[] {"POST", base + "/" + id + "/refunds", body(map("amount", 1, "refundedOn", TODAY, "note", "x"))});
        all.add(new String[] {"POST", base + "/" + id + "/listing", body(map("listing", "hidden"))});
        return all;
    }

    private static Browser.Answer call(Browser browser, String[] operation) {
        if (operation[0].equals("GET")) {
            return browser.get(operation[1]);
        }
        browser.extraHeaders.put("Idempotency-Key", key());
        try {
            return operation[2] == null ? browser.postEmpty(operation[1]) : browser.post(operation[1], operation[2]);
        } finally {
            browser.extraHeaders.remove("Idempotency-Key");
        }
    }

    private static String withoutRequestId(Browser.Answer answer) {
        return answer.body().replaceAll("\"requestId\":\"[0-9a-f]+\"", "");
    }

    @Test
    void d8_1_aDonorAndAnAdministratorAreRefusedEveryReviewOperationBeforeAnyGiftIsLookedAt() {
        Reported gift = report("HELP-904", 5000, zelle());
        String existing = giftId(gift.reference());
        Browser donor = donor("sample.donor@example.org");
        Staff admin = staff("sample.admin@example.org", "admin");
        long historyBefore = count("select count(*) from donation_history");

        for (Browser browser : List.of(donor, admin.browser())) {
            List<String[]> forExisting = operations(existing);
            List<String[]> forUnknown = operations(UNKNOWN);
            List<String[]> forMalformed = operations("not-an-id");
            for (int i = 0; i < forExisting.size(); i++) {
                Browser.Answer real = call(browser, forExisting.get(i));
                assertError(real, 403, "forbidden");
                // The same answer whether the gift exists, does not, or could not: the identifier was never looked at.
                assertThat(withoutRequestId(call(browser, forUnknown.get(i)))).as(forExisting.get(i)[1]).isEqualTo(withoutRequestId(real));
                assertThat(withoutRequestId(call(browser, forMalformed.get(i)))).as(forExisting.get(i)[1]).isEqualTo(withoutRequestId(real));
            }
        }
        assertThat(count("select count(*) from donation where state = 'reported'")).isEqualTo(1);
        assertThat(count("select count(*) from donation_history")).isEqualTo(historyBefore);
        assertThat(count("select count(*) from idempotency_record where operation <> 'report'")).isZero();
        // Each refusal is recorded with the account and the operation.
        assertThat(count("select count(*) from audit_event where event_type = 'staff.permission_refused' and outcome = 'refused'"
                + " and details ->> 'permission' = 'donations.confirm'")).isEqualTo(66);
        assertThat(count("select count(distinct details ->> 'operation') from audit_event where event_type = 'staff.permission_refused'")).isEqualTo(11);
        // Neither is told the permission exists for them.
        assertThat(donor.get("/api/me").json().get("permissions").toString()).doesNotContain("donations");
        assertThat(admin.browser().get("/api/me").json().get("permissions").toString()).doesNotContain("donations");
    }

    @Test
    void d8_2_signedOutAReadIsUnauthenticatedAndAWriteWithAStaleTokenIsRefusedAtTheToken() {
        Reported gift = report("HELP-904", 5000, zelle());
        String id = giftId(gift.reference());
        Staff owner = owner();
        String staleToken = owner.browser().token;
        assertThat(owner.browser().postEmpty("/api/auth/logout").status()).isEqualTo(204);

        Browser signedOut = browser();
        for (String[] operation : operations(id)) {
            if (operation[0].equals("GET")) {
                assertError(signedOut.get(operation[1]), 401, "unauthenticated");
                assertError(owner.browser().get(operation[1]), 401, "unauthenticated");
            } else {
                // The session is gone; its token is refused first, and nothing about the request is examined.
                owner.browser().token = staleToken;
                assertError(call(owner.browser(), operation), 403, "csrf_invalid");
            }
        }
        // An anonymous session with its own valid token is not signed in: 401, at step 6.
        Browser anonymous = visitor();
        for (String[] operation : operations(id)) {
            assertError(call(anonymous, operation), 401, "unauthenticated");
        }
        assertThat(count("select count(*) from donation where state = 'reported'")).isEqualTo(1);
    }

    @Test
    void d8_3_d8_4_d8_5_onlyAFullySignedInSuperAdministratorHoldsThePermission() {
        String details = zelle();
        Staff owner = owner();
        assertThat(owner.browser().get("/api/me").json().get("permissions").toString())
                .isEqualTo("[\"account.read_own\",\"account.change_password\",\"donations.confirm\"]");

        // A session that still owes its second factor has no permission at all.
        Browser pending = pending(owner);
        pending.csrf();
        for (String[] operation : operations(UNKNOWN)) {
            assertError(call(pending, operation), 401, "mfa_required");
        }
        // On the giving operations it is a guest: the report is accepted and linked to nobody.
        Reported gift = reported(post(pending, "/api/donations", body(reportOf("HELP-904", 5000, details)), key()));
        assertThat(count("select count(*) from donation where reference = '" + gift.reference() + "' and account_id is null")).isEqualTo(1);
        assertThat(pending.get("/api/giving/methods").status()).isEqualTo(200);
        assertThat(status(pending, gift.trackingCode()).status()).isEqualTo(200);

        // The owner, fully signed in, reports as themselves like any account.
        Reported own = reported(post(owner.browser(), "/api/donations", body(reportOf("HELP-904", 700, details)), key()));
        assertThat(count("select count(*) from donation where reference = '" + own.reference() + "' and account_id is not null")).isEqualTo(1);
    }

    @Test
    void d8_6_aMalformedGiftIdentifierIsABadParameterNotAnUnknownPath() {
        Staff owner = owner();
        for (String malformed : List.of("not-an-id", "don_short", "don_AAAAAAAAAAAAAAAAAAAAA!", "7K4M-2QXD", "acc_AAAAAAAAAAAAAAAAAAAAAA")) {
            for (String[] operation : operations(malformed)) {
                if (operation[1].equals("/api/admin/donations")) {
                    continue;
                }
                Browser.Answer answer = call(owner.browser(), operation);
                assertFieldError(answer, "donationId", "invalid_format");
            }
        }
        // Well formed and unknown is a gift that does not exist; a path the contract does not have is not found at step 1.
        assertError(owner.browser().get("/api/admin/donations/" + UNKNOWN), 404, "donation_not_found");
        assertError(owner.browser().get("/api/admin/donations/" + UNKNOWN + "/history"), 404, "not_found");
        assertError(owner.browser().get("/api/admin"), 404, "not_found");
        Browser.Answer wrongMethod = owner.browser().send("PUT", "/api/admin/donations/" + UNKNOWN, "{}", "application/json", owner.browser().token);
        assertError(wrongMethod, 405, "method_not_allowed");
        assertThat(wrongMethod.header("Allow")).isEqualTo("GET, HEAD");
    }

    @Test
    void d8_7_d13_3_theOrderOfChecksIsTheOneOfIdentityAndARefusalBeforeStepEightConsumesNothing() {
        String details = zelle();
        Reported gift = report("HELP-904", 5000, details);
        String confirmPath = "/api/admin/donations/" + giftId(gift.reference()) + "/confirm";
        Staff owner = owner();
        Browser staffBrowser = owner.browser();

        // Step 1 before step 2: an unknown path from another site is unknown.
        staffBrowser.extraHeaders.put("Origin", "https://attacker.example");
        assertError(staffBrowser.post("/api/admin/nothing", "{}"), 404, "not_found");
        // Step 2 before step 3: from another site, a body that is too large and of the wrong type is still refused for where it came from.
        assertError(staffBrowser.send("POST", confirmPath, "x".repeat(20_000), "text/plain", staffBrowser.token), 403, "forbidden");
        staffBrowser.extraHeaders.remove("Origin");
        // Step 3 before step 4: size, then type, are judged before the token.
        assertError(staffBrowser.send("POST", confirmPath, "x".repeat(20_000), "text/plain", null), 413, "payload_too_large");
        assertError(staffBrowser.send("POST", confirmPath, "{}", "text/plain", null), 415, "unsupported_media_type");
        // Step 4 before step 6 and 7: without the token, nothing about the session or the body is said.
        assertError(staffBrowser.send("POST", confirmPath, "not json", "application/json", null), 403, "csrf_invalid");
        // Step 6 before step 7: an account without the permission is refused before its (invalid) body is read.
        Browser donor = donor("sample.donor@example.org");
        assertError(post(donor, confirmPath, "not json", key()), 403, "forbidden");
        // Step 7 before step 8: an invalid body is refused before the gift is looked at, and the key was not consumed.
        String key = key();
        assertFieldError(post(staffBrowser, "/api/admin/donations/don_AAAAAAAAAAAAAAAAAAAAAA/confirm", "not json", key), "body", "invalid_type");
        assertFieldError(post(staffBrowser, confirmPath, body(map("caseCode", "HELP-904")), key), "confirmedAmount", "required");
        assertFieldError(post(staffBrowser, confirmPath, body(confirmationOf("HELP-902", 5000)), key), "caseCode", "invalid_value");
        assertThat(count("select count(*) from idempotency_record where operation <> 'report'")).isZero();
        // The same key, now with a valid request, is a key not seen before.
        assertThat(post(staffBrowser, confirmPath, body(confirmationOf("HELP-904", 5000)), key).status()).isEqualTo(200);

        // The giving operations follow the same order. Step 5 before step 7: the seventh report of an address is limited even when it is invalid.
        Browser guest = visitor();
        guest.extraHeaders.put("Origin", "https://attacker.example");
        assertError(guest.send("POST", "/api/donations", "x", "text/plain", null), 403, "forbidden");
        guest.extraHeaders.remove("Origin");
        assertError(guest.send("POST", "/api/donations", "x", "text/plain", null), 415, "unsupported_media_type");
        assertError(guest.send("POST", "/api/donations", "{}", "application/json", null), 403, "csrf_invalid");
        for (int i = 0; i < 6; i++) {
            assertError(post(guest, "/api/donations", "not json", null), 400, "validation_error");
        }
        assertError(post(guest, "/api/donations", "not json", null), 429, "rate_limited");
        assertThat(count("select count(*) from donation")).isEqualTo(1);
    }

    /**
     * Scenario 8.8. The operator's disablement holds the account row exclusively until it
     * commits. A confirmation that begins meanwhile waits at its own re-read of the account
     * (identity-and-security.md 6.3), and when the disablement has committed it is refused: it
     * can never commit after the change.
     */
    @Test
    void d8_8_aSuperAdministratorDisabledWhileAConfirmationIsInFlightCannotCommitItAfterwards() throws Exception {
        Reported gift = report("HELP-904", 5000, zelle());
        String confirmPath = "/api/admin/donations/" + giftId(gift.reference()) + "/confirm";
        Staff owner = owner();
        // A second super administrator, so that the first can be disabled at all (identity-and-security.md, 8.6).
        staff("sample.second@example.org", "super_admin");
        long entries = count("select count(*) from ledger_entry");

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection disablement = dataSource.getConnection()) {
            disablement.setAutoCommit(false);
            try (Statement statement = disablement.createStatement()) {
                // What the operator's disable-account does, held open: the row is locked, the change not yet committed.
                statement.executeUpdate("update account set enabled = false, security_version = security_version + 1"
                        + " where email_normalized = 'sample.owner@example.org'");
            }
            Future<Browser.Answer> confirmation = executor.submit(
                    () -> post(owner.browser(), confirmPath, body(confirmationOf("HELP-904", 5000)), key()));
            // While the disablement is open the confirmation cannot finish, either way.
            try {
                confirmation.get(1500, TimeUnit.MILLISECONDS);
                throw new AssertionError("the confirmation finished while the account row was locked");
            } catch (TimeoutException stillWaiting) {
                // Expected.
            }
            assertThat(count("select count(*) from ledger_entry")).isEqualTo(entries);
            disablement.commit();
            Browser.Answer refused = confirmation.get(20, TimeUnit.SECONDS);
            assertError(refused, 401, "unauthenticated");
        } finally {
            executor.shutdownNow();
        }
        assertThat(count("select count(*) from ledger_entry")).isEqualTo(entries);
        assertThat(count("select count(*) from donation where state = 'reported'")).isEqualTo(1);
        assertThat(count("select count(*) from audit_event where event_type = 'donation.confirmed'")).isZero();
        // The session is over from the next request on.
        assertError(owner.browser().get("/api/admin/donations"), 401, "unauthenticated");
    }
}
