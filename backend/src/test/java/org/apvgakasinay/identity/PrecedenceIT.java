package org.apvgakasinay.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Security acceptance group 14: one request with several things wrong gets the answer of the first failing check. */
class PrecedenceIT extends IdentityIntegration {

    private static final String CHANGE = "/api/auth/change-password";
    private static final String EMAIL = "sample.donor@example.org";

    private String change() {
        return json("currentPassword", PASSWORD, "newPassword", OTHER_PASSWORD);
    }

    private String wrongChange() {
        return json("currentPassword", "not the right one at all", "newPassword", OTHER_PASSWORD);
    }

    private String ownOrigin() {
        return "http://127.0.0.1:" + port;
    }

    private void passwordStillIs(String password) {
        assertThat(browser().login(EMAIL, password).status()).isEqualTo(200);
    }

    @Test
    void a14_1_to_14_7_staleCredentialsOfTheBrowserAreRefusedByTheTokenCheck() {
        // 14.1: signed out.
        Browser signedOut = donor(EMAIL);
        Browser kept = signedOut.copyOfCookie();
        kept.token = signedOut.token;
        signedOut.postEmpty("/api/auth/logout");
        assertError(kept.post(CHANGE, change()), 403, "csrf_invalid");

        // 14.2: expired by idleness, row still there.
        Browser idle = browser();
        idle.login(EMAIL, PASSWORD);
        lastUsed(idle, Duration.ofHours(3));
        assertThat(count("select count(*) from spring_session where session_id = '" + idle.storedSessionId() + "'")).isEqualTo(1);
        assertError(idle.post(CHANGE, change()), 403, "csrf_invalid");

        // 14.3: the password was changed elsewhere.
        Browser here = browser();
        here.login(EMAIL, PASSWORD);
        Browser elsewhere = browser();
        elsewhere.login(EMAIL, PASSWORD);
        assertThat(elsewhere.post(CHANGE, change()).status()).isEqualTo(204);
        assertError(here.post(CHANGE, json("currentPassword", OTHER_PASSWORD, "newPassword", PASSWORD)), 403, "csrf_invalid");

        // 14.4: a live session with the token of its previous identifier.
        Browser live = browser();
        String before = live.csrf();
        assertThat(live.post("/api/auth/login", json("email", EMAIL, "password", OTHER_PASSWORD)).status()).isEqualTo(200);
        assertError(live.post(CHANGE, json("currentPassword", OTHER_PASSWORD, "newPassword", PASSWORD), before), 403, "csrf_invalid");

        // 14.5: another browser's valid token.
        Browser another = donor("another.donor@example.org");
        live.csrf();
        assertError(live.post(CHANGE, json("currentPassword", OTHER_PASSWORD, "newPassword", PASSWORD), another.token), 403, "csrf_invalid");

        // 14.6: a stale cookie and no token; 14.7: no cookie and a token that was once valid.
        assertError(kept.post(CHANGE, change(), null), 403, "csrf_invalid");
        assertError(browser().post(CHANGE, change(), kept.token), 403, "csrf_invalid");

        // Nothing was changed by any of them.
        passwordStillIs(OTHER_PASSWORD);
    }

    @Test
    void a14_8_to_14_10_aFreshAnonymousSessionWithItsValidToken() {
        register(browser(), EMAIL, PASSWORD);
        Browser browser = browser();
        browser.csrf();
        assertError(browser.post(CHANGE, change()), 401, "unauthenticated");
        assertError(browser.post("/api/auth/mfa/verify", json("method", "totp", "code", "123456")), 401, "unauthenticated");
        assertError(browser.postEmpty("/api/auth/mfa/enroll"), 401, "unauthenticated");
        assertError(browser.post("/api/auth/mfa/confirm", json("code", "123456")), 401, "unauthenticated");
        assertError(browser.post("/api/auth/mfa/recovery-codes", json("currentPassword", PASSWORD, "code", "123456")), 401, "unauthenticated");
        // 14.9 and 14.10
        assertThat(browser.post("/api/auth/login", json("email", EMAIL, "password", PASSWORD)).status()).isEqualTo(200);
        Browser other = browser();
        other.csrf();
        assertThat(other.postEmpty("/api/auth/logout").status()).isEqualTo(204);
    }

    @Test
    void a14_12_aDisabledAccountWhoseSessionRowsWereDeleted() {
        Browser donor = donor(EMAIL);
        staffAdministration.setEnabled(EMAIL, false, "test-operator");
        assertError(donor.get("/api/me"), 401, "unauthenticated");
        assertError(donor.post(CHANGE, change()), 403, "csrf_invalid");
    }

    @Test
    void a14_13_and_14_14_aRevokedIdentityWhoseRowSurvivedNeverReachesThePasswordCheck() {
        for (String body : List.of(wrongChange(), change())) {
            Browser donor = browser();
            if (count("select count(*) from account") == 0) {
                register(donor, EMAIL, PASSWORD);
            }
            donor.login(EMAIL, PASSWORD);
            String stored = donor.storedSessionId();
            jdbc.sql("update account set security_version = security_version + 1").update();
            jdbc.sql("delete from auth_throttle").update();

            Browser.Answer answer = donor.post(CHANGE, body);
            assertError(answer, 401, "unauthenticated");
            assertThat(count("select count(*) from auth_throttle where scope like 'password.%'")).as("the password counter did not move").isZero();
            assertThat(count("select count(*) from spring_session where session_id = '" + stored + "'")).as("the row is deleted").isZero();
        }
        passwordStillIs(PASSWORD);
    }

    @Test
    void a14_15_theSameForRecoveryCodes() {
        Staff admin = staff("sample.admin@example.org", "admin");
        jdbc.sql("update account set security_version = security_version + 1").update();
        jdbc.sql("delete from auth_throttle").update();
        Browser.Answer answer = admin.browser().post("/api/auth/mfa/recovery-codes", json("currentPassword", PASSWORD, "code", "000000"));
        assertError(answer, 401, "unauthenticated");
        assertThat(count("select count(*) from auth_throttle where scope like 'mfa.%' or scope like 'password.%'")).isZero();
        assertThat(count("select count(*) from account_recovery_code where used_at is null")).isEqualTo(10);
    }

    @Test
    void a14_16_aPendingSessionOfADisabledAccountDoesNotConsumeTheCode() {
        Staff admin = staff("sample.admin@example.org", "admin");
        Browser browser = pending(admin);
        long step = count("select last_accepted_step from account_totp");
        // Disabled without deleting the session row.
        jdbc.sql("update account set enabled = false").update();
        assertError(browser.post("/api/auth/mfa/verify", json("method", "totp", "code", code(admin.secret()))), 401, "unauthenticated");
        assertThat(count("select last_accepted_step from account_totp")).isEqualTo(step);
    }

    @Test
    void a14_17_aRevokedIdentityCallingSignInIsTreatedAsAnonymous() {
        register(browser(), "other.donor@example.org", PASSWORD);
        Browser donor = donor(EMAIL);
        jdbc.sql("update account set security_version = security_version + 1 where email = :email").param("email", EMAIL).update();
        Browser.Answer login = donor.post("/api/auth/login", json("email", "other.donor@example.org", "password", PASSWORD));
        assertThat(login.status()).as(login.body()).isEqualTo(200);
        assertThat(donor.get("/api/me").json().get("email").asText()).isEqualTo("other.donor@example.org");
    }

    @Test
    void a14_18_to_14_22_theEarlyChecksInOrder() {
        String huge = "x".repeat(1024 * 1024);
        // 14.18: an unknown path wins over everything.
        Browser unknown = browser();
        unknown.extraHeaders.put("Origin", "https://attacker.example");
        assertError(unknown.send("POST", "/api/auth/no-such-thing", huge, "text/plain", "bad-token-bad-token-bad-token"), 404, "not_found");

        // 14.19: then the method.
        Browser.Answer put = unknown.send("PUT", "/api/auth/login", "{}", "application/json", "bad-token-bad-token-bad-token");
        assertError(put, 405, "method_not_allowed");
        assertThat(put.header("Allow")).isEqualTo("POST");

        // 14.20: then the origin, before size, type and token.
        assertError(unknown.send("POST", "/api/auth/login", huge, "application/x-www-form-urlencoded", null), 403, "forbidden");

        // 14.21: own origin: size before type and token.
        Browser own = browser();
        own.extraHeaders.put("Origin", ownOrigin());
        assertError(own.send("POST", "/api/auth/login", huge, "text/plain", null), 413, "payload_too_large");
        // 14.22: type before token.
        assertError(own.send("POST", "/api/auth/login", "{}", "text/plain", null), 415, "unsupported_media_type");
        // And only then the token.
        assertError(own.send("POST", "/api/auth/login", "{}", "application/json", null), 403, "csrf_invalid");
    }

    @Test
    void a14_23_to_14_25_tokenThenRateLimitThenState() {
        Browser donor = donor(EMAIL);
        // 14.25: signed in, malformed body, wrong password: the state answers.
        assertError(donor.post("/api/auth/login", "{\"email\":5,\"password\":\"wrong\"}"), 409, "already_signed_in");
        // With the sign-in that made this session, thirty requests from this address in all.
        for (int i = 3; i <= 30; i++) {
            assertError(donor.post("/api/auth/login", "{}"), 409, "already_signed_in");
        }
        long counted = count("select sum(attempts) from auth_throttle where scope = 'signin.address'");
        // 14.23: no token: refused by the token check although the address is at its limit, and not counted.
        assertError(donor.post("/api/auth/login", "{}", null), 403, "csrf_invalid");
        assertThat(count("select sum(attempts) from auth_throttle where scope = 'signin.address'")).isEqualTo(counted);
        // 14.24: valid token, rate-limited, signed in, malformed: the rate limit answers.
        Browser.Answer limited = donor.post("/api/auth/login", "{\"email\":5}");
        assertError(limited, 429, "rate_limited");
        assertThat(limited.header("Retry-After")).isNotBlank();
        assertThat(donor.get("/api/me").status()).isEqualTo(200);
    }

    @Test
    void a14_26_and_14_27_stateBeforeValidationAndValidationBeforeTheCode() {
        Staff admin = staff("sample.admin@example.org", "admin");
        Browser anonymous = browser();
        anonymous.csrf();
        assertError(anonymous.post("/api/auth/mfa/verify", "{\"nonsense\":true}"), 401, "unauthenticated");

        Browser browser = pending(admin);
        for (int i = 0; i < 6; i++) {
            assertError(browser.post("/api/auth/mfa/verify", json("method", "totp", "code", "12345")), 400, "validation_error");
        }
        // Six malformed codes were not counted among the five: the sign-in is still pending and completes.
        assertThat(count("select coalesce(sum(attempts), 0) from auth_throttle where scope like 'mfa.%'")).isZero();
        assertThat(browser.post("/api/auth/mfa/verify", json("method", "totp", "code", code(admin.secret()))).status()).isEqualTo(200);
    }

    @Test
    void a14_28_and_14_29_validationBeforeThePasswordAndThePasswordCountedOnce() {
        Browser donor = donor(EMAIL);
        jdbc.sql("delete from auth_throttle").update();
        long failures = count("select count(*) from audit_event where event_type = 'account.password_change'");

        Browser.Answer invalid = donor.post(CHANGE, json("currentPassword", "not the right one at all", "newPassword", "too short"));
        assertError(invalid, 400, "validation_error");
        assertThat(invalid.fieldCode()).isEqualTo("too_short");
        assertThat(count("select count(*) from auth_throttle where scope = 'password.account'")).as("not checked, not counted").isZero();
        assertThat(count("select count(*) from audit_event where event_type = 'account.password_change'")).isEqualTo(failures);

        assertError(donor.post(CHANGE, wrongChange()), 401, "invalid_credentials");
        assertThat(count("select sum(attempts) from auth_throttle where scope = 'password.account'")).as("counted once").isEqualTo(1);
        assertThat(donor.get("/api/me").status()).as("still signed in").isEqualTo(200);
        // The same token still works: the session did not change.
        assertThat(donor.post(CHANGE, change()).status()).isEqualTo(204);
    }

    @Test
    void a14_31_and_14_32() {
        Staff admin = staff("sample.admin@example.org", "admin");
        Browser donor = donor(EMAIL);
        assertError(donor.post("/api/auth/logout", "{\"all\":true}", "bad-token-bad-token-bad-token"), 403, "csrf_invalid");
        assertThat(donor.get("/api/me").status()).isEqualTo(200);

        Browser awaitingCode = pending(admin);
        assertError(awaitingCode.postEmpty("/api/auth/mfa/enroll"), 403, "forbidden");
        assertError(awaitingCode.get("/api/me"), 401, "mfa_required");
        assertThat(count("select count(*) from account_totp where state = 'pending'")).isZero();
    }
}
