package org.apvgakasinay.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apvgakasinay.support.Json;
import org.apvgakasinay.support.Repo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Security acceptance groups 5 and 9: authorization, revocation, caching and security headers. */
class AuthorizationAndHeadersIT extends IdentityIntegration {

    @Autowired
    RequestMappingHandlerMapping mappings;

    @Autowired
    org.apvgakasinay.fixtures.FixtureImporter importer;

    @Test
    void a5_1_protectedRoutesAnswer401AsJsonNeverARedirect() {
        Browser.Answer me = browser().get("/api/me");
        assertError(me, 401, "unauthenticated");
        assertThat(me.header("Content-Type")).startsWith("application/json");
        assertThat(me.header("Location")).isNull();
        assertThat(me.header("WWW-Authenticate")).isNull();
        assertThat(me.headers().allValues("Set-Cookie")).isEmpty();
    }

    /**
     * Every route the application serves is a declared public read endpoint, a declared identity
     * operation, or an operation another module declared for the same order of checks (giving
     * and donation review, contract/donation-lifecycle.md 11).
     */
    @Test
    void a5_2_noRouteExistsWithoutADeclaredAccessRule() {
        List<String> publicRead = List.of("/api/site", "/api/stats", "/api/cases", "/api/cases/{code}", "/api/cases/{code}/updates",
                "/api/cases/{code}/donors", "/api/cases/{code}/ledger");
        int identity = 0;
        int donations = 0;
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mappings.getHandlerMethods().entrySet()) {
            boolean declaredIdentity = Arrays.stream(entry.getValue().getMethod().getAnnotations())
                    .map(Annotation::annotationType).anyMatch(type -> type.getSimpleName().equals("IdentityOperation"));
            org.apvgakasinay.shared.ApiOperation declared = entry.getValue().getMethodAnnotation(org.apvgakasinay.shared.ApiOperation.class);
            if (!entry.getValue().getBeanType().getPackageName().startsWith("org.apvgakasinay")) {
                // The framework's error page: not a route of this API. Asked for directly it does not exist.
                assertThat(entry.getKey().getPatternValues()).containsExactly("/error");
                Browser.Answer direct = browser().get("/error");
                assertThat(direct.status()).isEqualTo(404);
                assertThat(direct.code()).isEqualTo("not_found");
                continue;
            }
            for (String pattern : entry.getKey().getPatternValues()) {
                if (declaredIdentity) {
                    identity++;
                    assertThat(pattern.equals("/api/me") || pattern.startsWith("/api/auth/")).as(pattern).isTrue();
                } else if (declared != null) {
                    donations++;
                    boolean review = pattern.equals("/api/admin/donations") || pattern.startsWith("/api/admin/donations/");
                    boolean giving = List.of("/api/giving/methods", "/api/donations", "/api/donations/evidence", "/api/donations/status").contains(pattern);
                    assertThat(review || giving).as("operation outside the donation paths: " + pattern).isTrue();
                    // Review is for a fully signed-in session holding the permission; giving is open to everyone.
                    assertThat(declared.access()).as(pattern).isEqualTo(review
                            ? org.apvgakasinay.shared.ApiOperation.Access.SIGNED_IN : org.apvgakasinay.shared.ApiOperation.Access.ANY);
                    assertThat(declared.permission()).as(pattern).isEqualTo(review ? "donations.confirm" : "");
                } else {
                    boolean developmentPictures = pattern.startsWith("/media/public/");
                    assertThat(publicRead.contains(pattern) || developmentPictures).as("route with no declared rule: " + pattern).isTrue();
                    assertThat(entry.getKey().getMethodsCondition().getMethods().toString()).as(pattern).isEqualTo("[GET]");
                }
            }
        }
        assertThat(identity).as("the twelve identity operations").isEqualTo(12);
        assertThat(donations).as("the fifteen giving and donation review operations: four and eleven").isEqualTo(15);
    }

    @Test
    void a5_3_everyRoleGetsExactlyTheOperationalPermissions() {
        String expected = "[\"account.read_own\",\"account.change_password\"]";
        Browser donor = donor("sample.donor@example.org");
        Staff admin = staff("sample.admin@example.org", "admin");
        Staff owner = staff("sample.owner@example.org", "super_admin");
        for (Browser browser : List.of(donor, admin.browser())) {
            Browser.Answer me = browser.get("/api/me");
            assertValid("Account", me);
            assertThat(me.json().get("permissions").toString()).isEqualTo(expected);
        }
        // Operational since the donation review endpoints exist, for a super administrator only (donation-lifecycle.md, 6.1).
        Browser.Answer ownerAccount = owner.browser().get("/api/me");
        assertValid("Account", ownerAccount);
        assertThat(ownerAccount.json().get("permissions").toString())
                .isEqualTo("[\"account.read_own\",\"account.change_password\",\"donations.confirm\"]");
        assertThat(admin.browser().get("/api/me").json().at("/mfa/required").asBoolean()).isTrue();
        assertThat(admin.browser().get("/api/me").json().at("/mfa/recoveryCodesRemaining").asInt()).isEqualTo(10);
        assertThat(owner.browser().get("/api/me").json().get("role").asText()).isEqualTo("super_admin");

        // An administrator with the disbursement permission: still exactly the two, after signing in again.
        staffAdministration.setCanDisburse(admin.email(), true, "test-operator");
        Browser again = pending(admin);
        assertThat(again.post("/api/auth/mfa/verify", json("method", "totp", "code", code(admin.secret()))).status()).isEqualTo(200);
        assertThat(again.get("/api/me").json().get("permissions").toString()).isEqualTo(expected);
    }

    @Test
    void a5_8_noOperationAcceptsAnAccountIdentifier() {
        Browser one = donor("one@example.org");
        Browser two = donor("two@example.org");
        String otherId = two.get("/api/me").json().get("id").asText();
        Browser.Answer change = one.post("/api/auth/change-password",
                "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + OTHER_PASSWORD + "\",\"accountId\":\"" + otherId + "\"}");
        assertError(change, 400, "validation_error");
        assertThat(change.fieldCode()).isEqualTo("unknown_field");
        // An identifier in the address changes nothing: it is always the session's own account.
        assertThat(one.get("/api/me?id=" + otherId).json().get("email").asText()).isEqualTo("one@example.org");
        assertThat(browser().login("two@example.org", PASSWORD).status()).isEqualTo(200);
    }

    @Test
    void a5_9_removingAPermissionEndsTheSessionFirst() {
        Staff admin = staff("sample.admin@example.org", "admin");
        Browser second = pending(admin);
        assertThat(second.post("/api/auth/mfa/verify", json("method", "totp", "code", code(admin.secret()))).status()).isEqualTo(200);
        second.csrf();

        staffAdministration.setCanDisburse(admin.email(), true, "test-operator");
        // The very next GET is 401; the very next POST is refused by the CSRF check, then GET /me says why.
        assertError(admin.browser().get("/api/me"), 401, "unauthenticated");
        assertError(second.post("/api/auth/change-password", json("currentPassword", PASSWORD, "newPassword", OTHER_PASSWORD)), 403, "csrf_invalid");
        assertError(second.get("/api/me"), 401, "unauthenticated");
        assertThat(count("select count(*) from spring_session where principal_name is not null")).isZero();
    }

    @Test
    void a5_10_aDisabledAccountLosesItsSessionsAndCannotSignIn() {
        Browser donor = donor("sample.donor@example.org");
        staffAdministration.setEnabled("sample.donor@example.org", false, "test-operator");
        assertError(donor.get("/api/me"), 401, "unauthenticated");
        assertError(browser().login("sample.donor@example.org", PASSWORD), 401, "invalid_credentials");
        staffAdministration.setEnabled("sample.donor@example.org", true, "test-operator");
        assertThat(browser().login("sample.donor@example.org", PASSWORD).status()).isEqualTo(200);
    }

    @Test
    void a5_11_aSessionWhoseRowSurvivedIsRefusedByItsSecurityVersion() {
        Browser donor = donor("sample.donor@example.org");
        String stored = donor.storedSessionId();
        // The identity is revoked without deleting the session row.
        jdbc.sql("update account set security_version = security_version + 1").update();
        assertThat(count("select count(*) from spring_session where session_id = '" + stored + "'")).isEqualTo(1);

        assertError(donor.get("/api/me"), 401, "unauthenticated");
        assertThat(count("select count(*) from spring_session where session_id = '" + stored + "'")).isZero();
        assertThat(count("select count(*) from audit_event where event_type = 'session.revoked'")).isEqualTo(1);
    }

    @Test
    void a9_2_and_13_2_publicEndpointsDoNotDependOnTheSessionAndSetNoCookie() {
        importer.replaceAllData(Json.readMap(Repo.contract("fixtures/example.json")));
        clock.pin(START);
        Browser visitor = browser();
        Browser donor = donor("sample.donor@example.org");
        for (String path : List.of("/api/site", "/api/stats", "/api/cases", "/api/cases/HELP-901", "/api/cases/HELP-901/updates",
                "/api/cases/HELP-901/donors", "/api/cases/HELP-901/ledger")) {
            Browser.Answer anonymous = visitor.get(path);
            Browser.Answer signedIn = donor.get(path);
            assertThat(anonymous.status()).isEqualTo(200);
            assertThat(signedIn.body()).as(path).isEqualTo(anonymous.body());
            for (Browser.Answer answer : List.of(anonymous, signedIn)) {
                assertThat(answer.header("Cache-Control")).as(path).isEqualTo("no-cache");
                assertThat(answer.header("ETag")).as(path).isNotBlank();
                assertThat(answer.headers().allValues("Set-Cookie")).as(path).isEmpty();
            }
        }
        // A public read creates no session, for a visitor with no cookie.
        assertThat(visitor.sessionId).isNull();
    }

    @Test
    void a9_1_a9_3_a9_4_a9_5_headersOnEveryIdentityAnswer() {
        Staff admin = staff("sample.admin@example.org", "admin");
        Browser donor = donor("sample.donor@example.org");
        // A spread of answers: 200, 202, 204, 400, 401, 403, 405, 409, 413, 415.
        donor.get("/api/me");
        donor.get("/api/auth/options");
        donor.post("/api/auth/login", "{}");
        donor.post("/api/auth/change-password", "{\"currentPassword\":1}");
        donor.post("/api/auth/change-password", "{}", null);
        donor.send("PUT", "/api/auth/login", "{}", "application/json", donor.token);
        donor.send("POST", "/api/auth/change-password", "x".repeat(20_000), "application/json", donor.token);
        donor.send("POST", "/api/auth/change-password", "{}", "text/plain", donor.token);
        pending(admin).get("/api/me");
        browser().get("/api/me");
        donor.postEmpty("/api/auth/logout");

        List<Browser.Answer> identity = Browser.RECORDED.stream().filter(a -> a.path().startsWith("/api/auth/") || a.path().startsWith("/api/me")).toList();
        assertThat(identity.stream().map(Browser.Answer::status).distinct().toList())
                .contains(200, 202, 204, 400, 401, 403, 405, 409, 413, 415);
        for (Browser.Answer answer : identity) {
            String what = answer.method() + " " + answer.path() + " " + answer.status();
            // 9.1: never stored, at every status, and no ETag.
            assertThat(answer.header("Cache-Control")).as(what).isEqualTo("no-store");
            assertThat(answer.header("ETag")).as(what).isNull();
            // 9.4
            assertThat(answer.header("X-Content-Type-Options")).as(what).isEqualTo("nosniff");
            assertThat(answer.header("X-Frame-Options")).as(what).isEqualTo("DENY");
            assertThat(answer.header("Referrer-Policy")).as(what).isEqualTo("no-referrer");
            assertThat(answer.header("Content-Security-Policy")).as(what).isEqualTo("default-src 'none'; frame-ancestors 'none'");
            assertThat(answer.header("Cross-Origin-Resource-Policy")).as(what).isEqualTo("same-origin");
            // 9.5: no cross-origin grant, ever.
            assertThat(answer.headers().map().keySet()).as(what).noneMatch(name -> name.toLowerCase().startsWith("access-control-"));
            // 9.3
            if (answer.status() == 405) {
                assertThat(answer.header("Allow")).as(what).isNotBlank();
            }
            if (answer.status() >= 400) {
                assertValid("Error", answer);
            }
        }
    }

    @Test
    void a9_10_aServerErrorSaysNothingAboutTheServer() {
        register(browser(), "sample.donor@example.org", PASSWORD);
        Browser browser = browser();
        browser.csrf();
        // Forced: the table the password check reads is out of reach for one request.
        jdbc.sql("alter table account_password rename to account_password_hidden").update();
        Browser.Answer answer;
        try {
            answer = browser.post("/api/auth/login", json("email", "sample.donor@example.org", "password", PASSWORD));
        } finally {
            jdbc.sql("alter table account_password_hidden rename to account_password").update();
        }
        assertError(answer, 500, "internal_error");
        assertThat(answer.header("Cache-Control")).isEqualTo("no-store");
        assertThat(answer.body()).doesNotContainIgnoringCase("exception").doesNotContainIgnoringCase("sql")
                .doesNotContain("account_password").doesNotContain("org.").doesNotContain("\tat ").doesNotContain("relation");
        assertThat(answer.json().at("/error/message").asText()).isEqualTo("Unexpected error");
        // Nobody was signed in by the failure, and the server works again afterwards.
        assertError(browser.get("/api/me"), 401, "unauthenticated");
        assertThat(browser().login("sample.donor@example.org", PASSWORD).status()).isEqualTo(200);
    }

    @Test
    void a5_12_and_5_13_aWriteRacingARevocationNeverLeavesALiveSession() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 5; round++) {
                String email = "racing.donor" + round + "@example.org";
                Browser donor = donor(email);
                String change = json("currentPassword", PASSWORD, "newPassword", OTHER_PASSWORD);
                java.util.concurrent.Future<Integer> write = pool.submit(() -> donor.post("/api/auth/change-password", change).status());
                java.util.concurrent.Future<?> revocation = pool.submit(() -> staffAdministration.setEnabled(email, false, "test-operator"));
                int status = write.get();
                revocation.get();
                // The write either finished first (204) or was refused; in no case does a usable session remain.
                assertThat(status).as("round %d", round).isIn(204, 401, 403);
                assertError(donor.get("/api/me"), 401, "unauthenticated");
                donor.csrf();
                assertError(donor.post("/api/auth/change-password", change), 401, "unauthenticated");
                assertError(browser().login(email, PASSWORD), 401, "invalid_credentials");
                assertError(browser().login(email, OTHER_PASSWORD), 401, "invalid_credentials");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void a9_6_and_9_7_bodies() {
        Browser donor = donor("sample.donor@example.org");
        assertError(donor.send("POST", "/api/auth/change-password", "{\"currentPassword\":\"" + "x".repeat(17_000) + "\"}", "application/json", donor.token), 413, "payload_too_large");
        Browser.Answer notJson = donor.post("/api/auth/change-password", "this is not json");
        assertError(notJson, 400, "validation_error");
        assertThat(notJson.fieldCode()).isEqualTo("invalid_type");
        assertError(donor.post("/api/auth/change-password", "[1,2,3]"), 400, "validation_error");
        assertError(donor.send("POST", "/api/auth/change-password", "{}", "text/plain", donor.token), 415, "unsupported_media_type");
        assertError(donor.send("POST", "/api/auth/change-password", "{}", null, donor.token), 415, "unsupported_media_type");
        assertThat(donor.post("/api/auth/change-password", "{}").fieldCode()).isEqualTo("required");
        assertThat(donor.post("/api/auth/change-password", "{\"currentPassword\":5,\"newPassword\":\"x\"}").fieldCode()).isEqualTo("invalid_type");

        // 9.7: operations that define no body take none.
        Browser.Answer withBody = donor.post("/api/auth/logout", "{}");
        assertError(withBody, 400, "validation_error");
        assertThat(donor.get("/api/me").status()).as("the refused sign-out did not sign out").isEqualTo(200);
        assertThat(donor.postEmpty("/api/auth/logout").status()).isEqualTo(204);
    }
}
