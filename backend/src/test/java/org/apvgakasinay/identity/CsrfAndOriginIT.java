package org.apvgakasinay.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Security acceptance groups 4 and 11: the session-bound CSRF token, the origin check, methods and routes. */
class CsrfAndOriginIT extends IdentityIntegration {

    private static final List<String> POSTS_WITH_BODY = List.of("/api/auth/register", "/api/auth/login", "/api/auth/change-password",
            "/api/auth/set-password", "/api/auth/mfa/verify", "/api/auth/mfa/confirm", "/api/auth/mfa/recovery-codes");
    private static final List<String> POSTS_WITHOUT_BODY = List.of("/api/auth/logout", "/api/auth/mfa/enroll");

    private String ownOrigin() {
        return "http://127.0.0.1:" + port;
    }

    private String loginBody() {
        return json("email", "sample.donor@example.org", "password", PASSWORD);
    }

    @Test
    void a4_1_theTokenEndpointBootstrapsAnAnonymousSession() {
        Browser browser = browser();
        Browser.Answer answer = browser.get("/api/auth/csrf");
        assertThat(answer.status()).isEqualTo(200);
        assertValid("CsrfToken", answer);
        assertThat(answer.json().get("headerName").asText()).isEqualTo("X-CSRF-TOKEN");
        assertThat(answer.header("Cache-Control")).isEqualTo("no-store");
        assertThat(answer.header("ETag")).isNull();
        assertThat(answer.headers().allValues("Set-Cookie")).hasSize(1);
        assertThat(browser.sessionId).isNotNull();
        assertThat(count("select count(*) from spring_session where principal_name is null")).isEqualTo(1);
    }

    @Test
    void a4_2_a4_3_a4_7_everyPostNeedsTheTokenIncludingThePublicOnes() {
        register(browser(), "sample.donor@example.org", PASSWORD);
        Browser browser = browser();
        browser.csrf();
        for (String path : POSTS_WITH_BODY) {
            assertError(browser.post(path, "{}", null), 403, "csrf_invalid");
            assertError(browser.post(path, "{}", "a-made-up-token-that-is-long-enough"), 403, "csrf_invalid");
        }
        for (String path : POSTS_WITHOUT_BODY) {
            assertError(browser.send("POST", path, null, null, null), 403, "csrf_invalid");
            assertError(browser.send("POST", path, null, null, "a-made-up-token-that-is-long-enough"), 403, "csrf_invalid");
        }
        // A correct password with no token signs nobody in.
        assertError(browser.post("/api/auth/login", loginBody(), null), 403, "csrf_invalid");
        assertError(browser.get("/api/me"), 401, "unauthenticated");
        // With no session at all, the same.
        assertError(browser().post("/api/auth/login", loginBody(), "a-made-up-token-that-is-long-enough"), 403, "csrf_invalid");
    }

    @Test
    void a4_4_a4_6_aTokenDiesWithItsSession() {
        Browser donor = browser();
        register(donor, "sample.donor@example.org", PASSWORD);
        String before = donor.csrf();
        assertThat(donor.post("/api/auth/login", loginBody()).status()).isEqualTo(200);
        String change = json("currentPassword", PASSWORD, "newPassword", OTHER_PASSWORD);
        // 4.4: fetched before sign-in, used after.
        assertError(donor.post("/api/auth/change-password", change, before), 403, "csrf_invalid");
        String signedIn = donor.csrf();
        assertThat(donor.post("/api/auth/change-password", change, signedIn).status()).isEqualTo(204);
        // 4.6: after a password change the earlier token is dead, and nothing more was changed.
        assertError(donor.post("/api/auth/change-password", json("currentPassword", OTHER_PASSWORD, "newPassword", PASSWORD), signedIn), 403, "csrf_invalid");
        assertThat(browser().login("sample.donor@example.org", OTHER_PASSWORD).status()).isEqualTo(200);

        // 4.6: after sign-out, with the old cookie, with a new cookie and with none.
        String last = donor.csrf();
        Browser oldCookie = donor.copyOfCookie();
        assertThat(donor.postEmpty("/api/auth/logout").status()).isEqualTo(204);
        assertError(oldCookie.post("/api/auth/change-password", change, last), 403, "csrf_invalid");
        Browser newCookie = browser();
        newCookie.csrf();
        assertError(newCookie.post("/api/auth/change-password", change, last), 403, "csrf_invalid");
        assertError(browser().post("/api/auth/change-password", change, last), 403, "csrf_invalid");
    }

    @Test
    void a4_5_aTokenBelongsToOneBrowser() {
        Browser one = donor("one@example.org");
        Browser two = donor("two@example.org");
        assertError(two.post("/api/auth/change-password", json("currentPassword", PASSWORD, "newPassword", OTHER_PASSWORD), one.token), 403, "csrf_invalid");
        assertThat(browser().login("two@example.org", PASSWORD).status()).isEqualTo(200);
    }

    @Test
    void a4_6_aTokenOfAnExpiredSessionIsRefused() {
        Browser donor = donor("sample.donor@example.org");
        advance(Duration.ofHours(24));
        assertError(donor.post("/api/auth/change-password", json("currentPassword", PASSWORD, "newPassword", OTHER_PASSWORD)), 403, "csrf_invalid");
        assertThat(browser().login("sample.donor@example.org", PASSWORD).status()).isEqualTo(200);
    }

    @Test
    void a4_8_twoTokensOfOneSessionDifferAndBothWork() {
        register(browser(), "sample.donor@example.org", PASSWORD);
        Browser browser = browser();
        String first = browser.csrf();
        String second = browser.csrf();
        assertThat(first).isNotEqualTo(second);
        assertError(browser.post("/api/auth/login", json("email", "sample.donor@example.org", "password", OTHER_PASSWORD), first), 401, "invalid_credentials");
        assertThat(browser.post("/api/auth/login", loginBody(), second).status()).isEqualTo(200);
    }

    @Test
    void a4_9_aRefusedTokenCountsAgainstNothing() {
        register(browser(), "sample.donor@example.org", PASSWORD);
        long auditBefore = count("select count(*) from audit_event");
        Browser browser = browser();
        browser.csrf();
        jdbc.sql("delete from auth_throttle").update();
        for (int i = 0; i < 6; i++) {
            assertError(browser.post("/api/auth/login", json("email", "sample.donor@example.org", "password", OTHER_PASSWORD), "stale-token-stale-token-stale-token"), 403, "csrf_invalid");
        }
        // The rejection came before the rate limit and before the password: nothing was counted or recorded.
        assertThat(count("select count(*) from auth_throttle")).isZero();
        assertThat(count("select count(*) from audit_event")).isEqualTo(auditBefore);
        assertThat(browser.post("/api/auth/login", loginBody()).status()).isEqualTo(200);
    }

    @Test
    void a4_10_and_4_11_requestsFromAnotherSiteAreRefusedBeforeAnythingElse() {
        register(browser(), "sample.donor@example.org", PASSWORD);
        // 4.10: a cross-site form post, as a browser sends it.
        Browser form = browser();
        form.csrf();
        form.extraHeaders.put("Origin", "https://attacker.example");
        assertError(form.send("POST", "/api/auth/login", "email=sample.donor%40example.org", "application/x-www-form-urlencoded", null), 403, "forbidden");

        // 4.11: each of these is refused as "forbidden", never as "csrf_invalid", with a good token or a bad one.
        String[][] cases = {
                {"Origin", "https://attacker.example"},
                {"Origin", "null"},
                {"Sec-Fetch-Site", "cross-site"},
                {"Sec-Fetch-Site", "same-site"},
                {"Sec-Fetch-Site", "none"},
        };
        for (String[] header : cases) {
            Browser browser = browser();
            browser.csrf();
            browser.extraHeaders.put(header[0], header[1]);
            assertError(browser.post("/api/auth/login", loginBody()), 403, "forbidden");
            assertError(browser.post("/api/auth/login", loginBody(), null), 403, "forbidden");
        }
        Browser mixed = browser();
        mixed.csrf();
        mixed.extraHeaders.put("Origin", ownOrigin());
        mixed.extraHeaders.put("Sec-Fetch-Site", "cross-site");
        assertError(mixed.post("/api/auth/login", loginBody()), 403, "forbidden");
        // Nobody was signed in and nothing was counted as a failed sign-in.
        assertThat(count("select count(*) from audit_event where event_type = 'session.sign_in'")).isZero();
    }

    @Test
    void a4_10a_and_4_11a_requestsFromThisSitePassTheOriginCheck() {
        register(browser(), "sample.donor@example.org", PASSWORD);
        // No origin headers at all: the declared type is what is refused next.
        Browser plain = browser();
        plain.csrf();
        assertError(plain.send("POST", "/api/auth/login", "email=x", "application/x-www-form-urlencoded", plain.token), 415, "unsupported_media_type");

        Browser both = browser();
        both.csrf();
        both.extraHeaders.put("Origin", ownOrigin());
        both.extraHeaders.put("Sec-Fetch-Site", "same-origin");
        assertThat(both.post("/api/auth/login", loginBody()).status()).isEqualTo(200);

        Browser originOnly = browser();
        originOnly.csrf();
        originOnly.extraHeaders.put("Origin", ownOrigin());
        assertThat(originOnly.post("/api/auth/login", loginBody()).status()).isEqualTo(200);
    }

    @Test
    void a4_11b_and_4_12_getRequestsNeedNoTokenAndAreNotSubjectToTheOriginCheck() {
        Browser donor = donor("sample.donor@example.org");
        donor.extraHeaders.put("Origin", "https://another-site.example");
        donor.extraHeaders.put("Sec-Fetch-Site", "cross-site");
        assertThat(donor.get("/api/me").status()).isEqualTo(200);
        assertThat(donor.get("/api/site").status()).isEqualTo(200);
        assertThat(donor.get("/api/auth/options").status()).isEqualTo(200);
    }

    @Test
    void a11_1_knownPathsWithAnUnsupportedMethodAnswer405WithAllow() {
        Browser browser = browser();
        browser.csrf();
        Browser.Answer cases = browser.post("/api/cases", "{}");
        assertThat(cases.status()).isEqualTo(405);
        assertThat(cases.code()).isEqualTo("method_not_allowed");
        assertValid("Error", cases);
        assertThat(cases.header("Allow")).isEqualTo("GET, HEAD");

        Browser.Answer me = browser.send("DELETE", "/api/me", null, null, browser.token);
        assertError(me, 405, "method_not_allowed");
        assertThat(me.header("Allow")).isEqualTo("GET, HEAD");

        Browser.Answer login = browser.get("/api/auth/login");
        assertError(login, 405, "method_not_allowed");
        assertThat(login.header("Allow")).isEqualTo("POST");
    }

    @Test
    void a11_2_and_11_3_unknownPathsAndNoCrossOriginGrant() {
        Browser browser = browser();
        browser.csrf();
        for (String method : List.of("GET", "POST", "PUT", "DELETE")) {
            Browser.Answer answer = browser.send(method, "/api/auth/does-not-exist", null, null, browser.token);
            assertThat(answer.status()).as(method).isEqualTo(404);
            assertThat(answer.code()).isEqualTo("not_found");
        }
        assertThat(browser.get("/api/admin").status()).isEqualTo(404);
        assertThat(browser.get("/actuator/health").status()).isEqualTo(404);

        browser.extraHeaders.put("Origin", "https://another-site.example");
        browser.extraHeaders.put("Access-Control-Request-Method", "POST");
        for (String path : List.of("/api/auth/login", "/api/me", "/api/site")) {
            Browser.Answer preflight = browser.send("OPTIONS", path, null, null, null);
            assertThat(preflight.status()).isEqualTo(405);
            assertThat(preflight.headers().map().keySet()).noneMatch(name -> name.toLowerCase().startsWith("access-control-"));
        }
    }
}
