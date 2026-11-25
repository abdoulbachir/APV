package org.apvgakasinay.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Security acceptance group 3: sessions, their rotation, their two deadlines and their states. */
class SessionLifecycleIT extends IdentityIntegration {

    private static final String CHANGE = "/api/auth/change-password";

    private String changeBody() {
        return json("currentPassword", PASSWORD, "newPassword", OTHER_PASSWORD);
    }

    @Test
    void a3_1_anIdentifierKnownBeforeSignInIsWorthlessAfterwards() {
        Browser browser = browser();
        browser.csrf();
        Browser planted = browser.copyOfCookie();
        register(browser(), "sample.donor@example.org", PASSWORD);
        assertThat(browser.post("/api/auth/login", json("email", "sample.donor@example.org", "password", PASSWORD)).status()).isEqualTo(200);
        assertThat(browser.sessionId).isNotEqualTo(planted.sessionId);
        assertThat(browser.get("/api/me").status()).isEqualTo(200);
        assertError(planted.get("/api/me"), 401, "unauthenticated");
    }

    @Test
    void a3_2_theIdentifierChangesAtEveryChangeOfPrivilege() {
        Staff admin = staff("sample.admin@example.org", "admin");
        Set<String> identifiers = new LinkedHashSet<>();

        Browser browser = browser();
        browser.csrf();
        identifiers.add(browser.sessionId);
        Browser anonymous = browser.copyOfCookie();
        browser.post("/api/auth/login", json("email", admin.email(), "password", PASSWORD));
        identifiers.add(browser.sessionId);
        Browser pendingCopy = browser.copyOfCookie();
        browser.csrf();
        assertThat(browser.post("/api/auth/mfa/verify", json("method", "totp", "code", code(admin.secret()))).status()).isEqualTo(200);
        identifiers.add(browser.sessionId);
        Browser signedInCopy = browser.copyOfCookie();
        browser.csrf();
        assertThat(browser.post(CHANGE, changeBody()).status()).isEqualTo(204);
        identifiers.add(browser.sessionId);

        assertThat(identifiers).as("four different identifiers").hasSize(4);
        for (Browser old : List.of(anonymous, pendingCopy, signedInCopy)) {
            assertError(old.get("/api/me"), 401, "unauthenticated");
        }
        assertThat(browser.get("/api/me").status()).isEqualTo(200);
    }

    @Test
    void a3_3_idleTimeoutByRoleWhileTheRowIsStillThere() {
        Browser donor = donor("sample.donor@example.org");
        Staff admin = staff("sample.admin@example.org", "admin");

        // 31 minutes of silence: past the staff limit, well inside the donor limit.
        lastUsed(donor, Duration.ofMinutes(31));
        lastUsed(admin.browser(), Duration.ofMinutes(31));
        assertThat(count("select count(*) from spring_session where expiry_time < extract(epoch from now()) * 1000")).isEqualTo(1);
        assertThat(donor.get("/api/me").status()).isEqualTo(200);
        assertError(admin.browser().get("/api/me"), 401, "unauthenticated");

        lastUsed(donor, Duration.ofHours(2).plusSeconds(5));
        assertError(donor.get("/api/me"), 401, "unauthenticated");
        // The page's next POST still carries the dead session's token: refused by the CSRF check first.
        assertError(donor.post(CHANGE, changeBody()), 403, "csrf_invalid");
    }

    @Test
    void a3_4_activityNeverMovesTheAbsoluteDeadline() {
        Browser donor = donor("sample.donor@example.org");
        String deadline = donor.get("/api/me").json().at("/session/absoluteExpiresAt").asText();
        assertThat(deadline).isEqualTo("2026-11-25T12:00:00Z");
        for (int hour = 1; hour <= 23; hour++) {
            advance(Duration.ofHours(1));
            Browser.Answer me = donor.get("/api/me");
            assertThat(me.status()).as("hour %d", hour).isEqualTo(200);
            assertThat(me.json().at("/session/absoluteExpiresAt").asText()).isEqualTo(deadline);
        }
        advance(Duration.ofHours(1));
        // At the deadline itself, although the session was used an hour ago and its row is fresh.
        assertError(donor.get("/api/me"), 401, "unauthenticated");
        assertError(donor.post(CHANGE, changeBody()), 403, "csrf_invalid");
    }

    @Test
    void a3_5_deadlinesByKindOfSession() {
        Browser visitor = browser();
        visitor.csrf();
        assertThat(count("select max_inactive_interval from spring_session where session_id = '" + visitor.storedSessionId() + "'")).isEqualTo(1800);

        JsonNode donor = donor("sample.donor@example.org").get("/api/me").json().get("session");
        assertThat(donor.get("idleExpiresAt").asText()).isEqualTo("2026-11-24T14:00:00Z");
        assertThat(donor.get("absoluteExpiresAt").asText()).isEqualTo("2026-11-25T12:00:00Z");

        Staff admin = staff("sample.admin@example.org", "admin");
        JsonNode staff = admin.browser().get("/api/me").json().get("session");
        // Thirty minutes from now; twelve hours from the password step, which was at the start.
        assertThat(staff.get("idleExpiresAt").asText()).isEqualTo("2026-11-24T12:30:30Z");
        assertThat(staff.get("absoluteExpiresAt").asText()).isEqualTo("2026-11-25T00:00:00Z");

        Browser.Answer pendingLogin = browser().login(admin.email(), PASSWORD);
        assertValid("SecondFactorPending", pendingLogin);
        assertThat(pendingLogin.json().get("pendingExpiresAt").asText()).isEqualTo("2026-11-24T12:10:30Z");
        assertThat(count("select count(*) from spring_session where max_inactive_interval = 600")).isEqualTo(1);
        assertThat(count("select count(*) from spring_session where max_inactive_interval = 7200")).isEqualTo(1);
    }

    @Test
    void a3_6_and_3_7_signOut() {
        Browser donor = donor("sample.donor@example.org");
        Browser stale = donor.copyOfCookie();
        stale.token = donor.token;
        String stored = donor.storedSessionId();

        Browser.Answer out = donor.postEmpty("/api/auth/logout");
        assertThat(out.status()).isEqualTo(204);
        assertThat(out.header("Cache-Control")).isEqualTo("no-store");
        assertThat(count("select count(*) from spring_session where session_id = '" + stored + "'")).isZero();
        assertError(stale.get("/api/me"), 401, "unauthenticated");
        assertError(stale.post(CHANGE, changeBody()), 403, "csrf_invalid");

        // With no session, and twice: it can always be called.
        Browser nobody = browser();
        nobody.csrf();
        assertThat(nobody.postEmpty("/api/auth/logout").status()).isEqualTo(204);
        nobody.csrf();
        assertThat(nobody.postEmpty("/api/auth/logout").status()).isEqualTo(204);
    }

    @Test
    void a3_8_aPasswordChangeEndsEveryOtherSession() {
        Browser first = donor("sample.donor@example.org");
        Browser second = browser();
        second.login("sample.donor@example.org", PASSWORD);
        Browser third = browser();
        third.login("sample.donor@example.org", PASSWORD);

        assertThat(first.post(CHANGE, changeBody()).status()).isEqualTo(204);
        assertError(second.get("/api/me"), 401, "unauthenticated");
        assertError(third.post(CHANGE, changeBody()), 403, "csrf_invalid");
        assertThat(first.get("/api/me").status()).isEqualTo(200);
    }

    @Test
    void a3_9_oneCookieWithTheRightAttributes() {
        Browser.Answer answer = browser().get("/api/auth/csrf");
        List<String> cookies = answer.headers().allValues("Set-Cookie");
        assertThat(cookies).as("one cookie only: there is no CSRF cookie").hasSize(1);
        String cookie = cookies.get(0);
        assertThat(cookie).startsWith("apv_session=").contains("HttpOnly").contains("SameSite=Lax").contains("Path=/");
        assertThat(cookie).doesNotContainIgnoringCase("Domain=").doesNotContainIgnoringCase("Max-Age").doesNotContainIgnoringCase("Expires");
    }

    @Test
    void a3_12_and_3_13_sessionsThatGrantNothing() {
        Browser forged = browser();
        forged.sessionId = "bmV2ZXItaXNzdWVkLW5ldmVyLWlzc3VlZC1uZXZlcg==";
        assertError(forged.get("/api/me"), 401, "unauthenticated");
        assertThat(count("select count(*) from spring_session")).isZero();

        Browser visitor = browser();
        visitor.csrf();
        assertError(visitor.get("/api/me"), 401, "unauthenticated");
        assertError(visitor.post(CHANGE, changeBody()), 401, "unauthenticated");
    }

    @Test
    void a3_14_aSignedInSessionIsNeverSignedInOver() {
        register(browser(), "other.donor@example.org", PASSWORD);
        Browser donor = donor("sample.donor@example.org");
        String credential = createStaff("sample.admin@example.org", "admin");

        assertError(donor.post("/api/auth/login", json("email", "other.donor@example.org", "password", PASSWORD)), 409, "already_signed_in");
        assertError(donor.post("/api/auth/register", json("email", "third@example.org", "password", PASSWORD, "displayName", "Third")), 409, "already_signed_in");
        assertError(donor.post("/api/auth/set-password", json("credential", credential, "newPassword", PASSWORD)), 409, "already_signed_in");

        // Nothing changed: same account, no third account, the credential is still unused.
        assertThat(donor.get("/api/me").json().get("email").asText()).isEqualTo("sample.donor@example.org");
        assertThat(count("select count(*) from account where email_normalized = 'third@example.org'")).isZero();
        assertThat(count("select count(*) from one_time_credential where used_at is null and cancelled_at is null")).isEqualTo(1);
    }

    @Test
    void a3_15_aPendingSignInIsReplacedByANewSignIn() {
        Staff admin = staff("sample.admin@example.org", "admin");
        register(browser(), "sample.donor@example.org", PASSWORD);
        Browser browser = pending(admin);
        Browser.Answer login = browser.login("sample.donor@example.org", PASSWORD);
        assertThat(login.status()).as(login.body()).isEqualTo(200);
        assertThat(browser.get("/api/me").json().get("role").asText()).isEqualTo("donor");
    }

    @Test
    void a3_16_aPendingSignInExpiresAfterTenMinutes() {
        Staff admin = staff("sample.admin@example.org", "admin");
        Browser browser = pending(admin);
        advance(Duration.ofMinutes(9));
        assertError(browser.get("/api/me"), 401, "mfa_required");
        advance(Duration.ofMinutes(1));

        // 3.16: with the token the page still holds, the dead session's token is refused first.
        String verify = json("method", "totp", "code", code(admin.secret()));
        assertError(browser.post("/api/auth/mfa/verify", verify), 403, "csrf_invalid");
        assertError(browser.get("/api/me"), 401, "unauthenticated");
        // 3.16a: with a fresh anonymous token it is 401, and the code is never accepted.
        browser.csrf();
        assertError(browser.post("/api/auth/mfa/verify", verify), 401, "unauthenticated");
        assertThat(count("select last_accepted_step from account_totp")).isLessThan(clock.now().getEpochSecond() / 30);
    }

    /** One request in one state, and the single answer the contract gives it. */
    private void expect(Browser browser, String method, String path, String body, int status, String code) {
        Browser.Answer answer = method.equals("GET") ? browser.get(path)
                : body == null ? browser.postEmpty(path) : browser.post(path, body);
        assertThat(answer.status()).as("%s %s -> %s", method, path, answer.body()).isEqualTo(status);
        assertThat(answer.code()).as("%s %s", method, path).isEqualTo(code);
    }

    @Test
    void a3_18_everyOperationInEveryWrongStateHasOneAnswer() {
        Staff admin = staff("sample.admin@example.org", "admin");
        Browser anonymous = browser();
        anonymous.csrf();
        Browser awaitingCode = pending(admin);
        Browser awaitingEnrollment = activatedStaff("new.admin@example.org", "admin");
        awaitingEnrollment.login("new.admin@example.org", PASSWORD);
        Browser donor = donor("sample.donor@example.org");
        Browser staff = admin.browser();

        // Not for a fully signed-in session: 409, for donor and staff alike.
        for (Browser signedIn : List.of(donor, staff)) {
            expect(signedIn, "POST", "/api/auth/register", "{}", 409, "already_signed_in");
            expect(signedIn, "POST", "/api/auth/login", "{}", 409, "already_signed_in");
            expect(signedIn, "POST", "/api/auth/set-password", "{}", 409, "already_signed_in");
            expect(signedIn, "POST", "/api/auth/mfa/verify", "{}", 409, "already_signed_in");
            expect(signedIn, "POST", "/api/auth/mfa/enroll", null, 409, "already_signed_in");
            expect(signedIn, "POST", "/api/auth/mfa/confirm", "{}", 409, "already_signed_in");
        }
        // Second-factor steps: nothing for an anonymous session, and each pending state has its own.
        expect(anonymous, "POST", "/api/auth/mfa/verify", "{}", 401, "unauthenticated");
        expect(anonymous, "POST", "/api/auth/mfa/enroll", null, 401, "unauthenticated");
        expect(anonymous, "POST", "/api/auth/mfa/confirm", "{}", 401, "unauthenticated");
        expect(awaitingEnrollment, "POST", "/api/auth/mfa/verify", "{}", 403, "forbidden");
        expect(awaitingCode, "POST", "/api/auth/mfa/enroll", null, 403, "forbidden");
        expect(awaitingCode, "POST", "/api/auth/mfa/confirm", "{}", 403, "forbidden");
        // Fully signed-in operations: the 401 code names what is still owed.
        for (String path : List.of("/api/auth/change-password", "/api/auth/mfa/recovery-codes")) {
            expect(anonymous, "POST", path, "{}", 401, "unauthenticated");
            expect(awaitingCode, "POST", path, "{}", 401, "mfa_required");
            expect(awaitingEnrollment, "POST", path, "{}", 401, "mfa_enrollment_required");
        }
        expect(anonymous, "GET", "/api/me", null, 401, "unauthenticated");
        expect(awaitingCode, "GET", "/api/me", null, 401, "mfa_required");
        expect(awaitingEnrollment, "GET", "/api/me", null, 401, "mfa_enrollment_required");
        // Recovery codes are a staff operation.
        expect(donor, "POST", "/api/auth/mfa/recovery-codes", "{}", 403, "forbidden");

        // None of those refusals changed anything: every session is still in its state.
        assertThat(donor.get("/api/me").status()).isEqualTo(200);
        assertThat(staff.get("/api/me").status()).isEqualTo(200);
        assertError(awaitingCode.get("/api/me"), 401, "mfa_required");
        assertError(awaitingEnrollment.get("/api/me"), 401, "mfa_enrollment_required");
        // And every state may fetch a token, read the options and sign out.
        for (Browser any : List.of(anonymous, awaitingCode, awaitingEnrollment, donor, staff)) {
            assertThat(any.get("/api/auth/options").status()).isEqualTo(200);
            any.csrf();
            assertThat(any.postEmpty("/api/auth/logout").status()).isEqualTo(204);
        }
    }
}
