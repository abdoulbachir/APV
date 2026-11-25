package org.apvgakasinay.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.apvgakasinay.identity.internal.Sessions;
import org.apvgakasinay.support.RawHttp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

/**
 * Regression tests for the defects the independent review of bfb1abb found (2026-11-25):
 * D1, D2, D4 and D5 here; D6 in SensitiveLogFilterTest; the real-database half of D5 in
 * RealDatabaseIT; the server's own error page of D4 also in ApiErrorReportValveTest.
 */
class IndependentReviewFindingsIT extends IdentityIntegration {

    private static final List<String> POSTS_WITH_BODY = List.of("/api/auth/register", "/api/auth/login", "/api/auth/change-password",
            "/api/auth/set-password", "/api/auth/mfa/verify", "/api/auth/mfa/confirm", "/api/auth/mfa/recovery-codes");
    private static final List<String> POSTS_WITHOUT_BODY = List.of("/api/auth/logout", "/api/auth/mfa/enroll");

    @Autowired
    Sessions sessions;

    private static boolean setsASession(Browser.Answer answer) {
        return answer.headers().allValues("Set-Cookie").stream().anyMatch(cookie -> cookie.matches("(?:__Host-)?apv_session=[^;]+.*"));
    }

    private static String cookieOf(String storedSessionId) {
        return Base64.getEncoder().encodeToString(storedSessionId.getBytes(StandardCharsets.UTF_8));
    }

    private long signIns(String outcome) {
        return jdbc.sql("select count(*) from audit_event where event_type = 'session.sign_in' and outcome = :outcome")
                .param("outcome", outcome).query(Long.class).single();
    }

    // ------------------------------------------------------------------------------------
    // D2
    // ------------------------------------------------------------------------------------

    /**
     * D2. A POST that failed the token check with no session was answered 403 and given a new
     * session: a row, a token row and a cookie, with no rate limit in front. Every POST
     * operation, with no cookie, with a cookie that names no session, and with a session that
     * holds no token, each with and without a token header: 403 csrf_invalid, nothing stored,
     * no cookie issued.
     */
    @Test
    void a4_1_aPostRefusedAtTheTokenCheckLeavesNoSessionNoTokenAndNoCookie() {
        Browser donor = donor("sample.donor@example.org");
        // A session that exists and holds no token: written directly, since no request leaves one.
        String bare = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        jdbc.sql("""
                        insert into spring_session (primary_id, session_id, creation_time, last_access_time, max_inactive_interval, expiry_time)
                        values (:primary, :id, :now, :now, 1800, :expiry)
                        """)
                .param("primary", UUID.randomUUID().toString()).param("id", bare).param("now", now).param("expiry", now + 1_800_000L).update();

        long sessionRows = count("select count(*) from spring_session");
        long attributeRows = count("select count(*) from spring_session_attributes");
        long retiredRows = count("select count(*) from spring_session_retired");
        long attempts = count("select coalesce(sum(attempts), 0) from auth_throttle");
        long audit = count("select count(*) from audit_event");

        List<String> paths = new ArrayList<>(POSTS_WITH_BODY);
        paths.addAll(POSTS_WITHOUT_BODY);
        Map<String, Integer> unexpected = new TreeMap<>();
        int sent = 0;
        for (String path : paths) {
            boolean body = POSTS_WITH_BODY.contains(path);
            for (int variant = 0; variant < 6; variant++) {
                Browser browser = browser();
                browser.sessionId = switch (variant / 2) {
                    case 0 -> null;
                    case 1 -> cookieOf(UUID.randomUUID().toString());
                    default -> cookieOf(bare);
                };
                // A genuine token of another session: the right header, the right shape, not this request's.
                String token = variant % 2 == 0 ? null : donor.token;
                Browser.Answer answer = browser.send("POST", path, body ? "{}" : null, body ? "application/json" : null, token);
                sent++;
                boolean cookieIssued = variant < 2 ? !answer.headers().allValues("Set-Cookie").isEmpty() : setsASession(answer);
                if (answer.status() != 403 || !"csrf_invalid".equals(answer.code()) || cookieIssued) {
                    unexpected.merge(path + " variant " + variant + " -> " + answer.status() + " " + answer.code() + (cookieIssued ? " with a cookie" : ""),
                            1, Integer::sum);
                }
            }
        }
        // The reviewer's reproduction: the same refused request, three hundred times, from one address.
        Browser one = browser();
        for (int i = 0; i < 300; i++) {
            one.sessionId = null;
            Browser.Answer answer = one.send("POST", "/api/auth/login", "{}", "application/json", null);
            sent++;
            if (answer.status() != 403 || !"csrf_invalid".equals(answer.code()) || !answer.headers().allValues("Set-Cookie").isEmpty()) {
                unexpected.merge("repeated /api/auth/login -> " + answer.status() + " " + answer.code(), 1, Integer::sum);
            }
        }
        assertThat(unexpected).as("refused POSTs that were not a bare 403 csrf_invalid").isEmpty();

        assertThat(count("select count(*) from spring_session")).as("session rows").isEqualTo(sessionRows);
        assertThat(count("select count(*) from spring_session_attributes")).as("session attribute rows").isEqualTo(attributeRows);
        assertThat(count("select count(*) from spring_session_retired")).isEqualTo(retiredRows);
        assertThat(count("select coalesce(sum(attempts), 0) from auth_throttle")).as("rate-limit counters").isEqualTo(attempts);
        assertThat(count("select count(*) from audit_event")).isEqualTo(audit);
        // The one way to an anonymous session is still there, and the donor is untouched.
        Browser fresh = browser();
        fresh.csrf();
        assertThat(fresh.sessionId).isNotNull();
        assertThat(count("select count(*) from spring_session")).isEqualTo(sessionRows + 1);
        assertThat(donor.get("/api/me").status()).isEqualTo(200);
        Evidence.write("refused-posts-create-no-session", sent + " POSTs refused at the token check (no cookie, unknown cookie, session without a token; "
                + "9 operations, then 300 repeats): all 403 csrf_invalid, 0 session rows, 0 token rows, 0 cookies, 0 counters moved");
    }

    // ------------------------------------------------------------------------------------
    // D1
    // ------------------------------------------------------------------------------------

    /** One round: a correct sign-in and a sign-out sent at the same moment on one anonymous session. Returns true when the sign-in was refused. */
    private boolean signInAgainstSignOut(String email, String expectedStatus) {
        Browser tab = browser();
        tab.csrf();
        Browser other = sameSession(tab);
        long successes = signIns("success");
        List<Browser.Answer> answers = together(List.of(
                () -> tab.post("/api/auth/login", json("email", email, "password", PASSWORD)),
                () -> other.postEmpty("/api/auth/logout")));
        Browser.Answer login = answers.get(0);
        Browser.Answer logout = answers.get(1);
        assertThat(login.status()).as("sign-in racing a sign-out: %s", login.body()).isIn(200, 403);

        if (logout.status() == 204) {
            if (login.status() == 403) {
                assertError(login, 403, "csrf_invalid");
                assertThat(setsASession(login)).as("a refused sign-in issued a session cookie").isFalse();
                assertThat(signIns("success")).as("success recorded for a sign-in that produced no session").isEqualTo(successes);
            } else {
                // The sign-in finished first and the sign-out then ended what it had made.
                assertThat(login.json().get("status").asText()).isEqualTo(expectedStatus);
                assertThat(signIns("success")).isEqualTo(successes + 1);
            }
            // Whichever finished first: the sign-out was answered, so nobody is signed in.
            assertThat(count("select count(*) from spring_session where principal_name is not null")).as("sessions that belong to someone").isZero();
            assertThat(tab.get("/api/me").status()).isEqualTo(401);
            assertThat(other.get("/api/me").status()).isEqualTo(401);
            return login.status() == 403;
        }
        // The sign-out reached the server after the sign-in had replaced the identifier: it is a
        // stale request like any other, and the sign-in stands.
        assertError(logout, 403, "csrf_invalid");
        assertThat(login.status()).isEqualTo(200);
        assertThat(signIns("success")).isEqualTo(successes + 1);
        tab.csrf();
        assertThat(tab.postEmpty("/api/auth/logout").status()).isEqualTo(204);
        return false;
    }

    /**
     * D1. A sign-in with the correct password and a sign-out, sent together on one anonymous
     * session: the sign-in was answered 500, and the audit log recorded a successful sign-in
     * that had produced no session. Now the sign-in is refused 403 csrf_invalid, as it would
     * be had it arrived just after the sign-out, and recorded as refused. For a donor and for
     * staff, whose sign-in ends in a pending session.
     */
    @Test
    void a3_6_aSignInWhoseSessionIsEndedBySignOutIsRefusedAndNotRecordedAsASuccess() {
        String donor = "sample.donor@example.org";
        assertThat(register(browser(), donor, PASSWORD).status()).isEqualTo(202);
        Staff owner = staff("sample.owner@example.org", "super_admin");
        assertThat(owner.browser().postEmpty("/api/auth/logout").status()).isEqualTo(204);

        int refused = 0;
        int rounds = 0;
        for (int round = 0; round < 12; round++) {
            refused += signInAgainstSignOut(donor, "authenticated") ? 1 : 0;
            rounds++;
        }
        for (int round = 0; round < 6; round++) {
            refused += signInAgainstSignOut(owner.email(), "mfa_required") ? 1 : 0;
            rounds++;
        }
        // Verifying a password takes far longer than signing out, so the sign-out wins; the test is about that order.
        assertThat(refused).as("rounds in which the sign-out ended the session before the sign-in could store it").isPositive();
        assertThat(count("select count(*) from audit_event where event_type = 'session.sign_in' and outcome = 'refused' and details ->> 'reason' = 'session_ended'"))
                .isEqualTo(refused);
        // The same people can sign in straight afterwards.
        assertThat(browser().login(donor, PASSWORD).status()).isEqualTo(200);
        Evidence.write("sign-in-against-sign-out", rounds + " rounds of a correct sign-in and a sign-out sent together on one session: "
                + refused + " sign-ins refused 403 csrf_invalid, 0 answered 500, nobody signed in after any round, "
                + "0 success records without a session");
    }

    /**
     * The other order of the same race, made on purpose: the sign-in has already replaced the
     * identifier when a sign-out that still holds the old one ends the session. Ending by the
     * old identifier alone would end nothing and leave the person signed in.
     */
    @Test
    void a3_6_aSignOutThatStillHoldsAReplacedIdentifierEndsTheSessionUnderItsNewOne() {
        String email = "sample.donor@example.org";
        assertThat(register(browser(), email, PASSWORD).status()).isEqualTo(202);
        Browser tab = browser();
        tab.csrf();
        String before = tab.storedSessionId();
        assertThat(tab.post("/api/auth/login", json("email", email, "password", PASSWORD)).status()).isEqualTo(200);
        String after = tab.storedSessionId();
        assertThat(after).isNotEqualTo(before);
        assertThat(count("select count(*) from spring_session where principal_name is not null")).isEqualTo(1);

        // An identifier that was never replaced, and one nobody ever had, end nothing.
        sessions.endSuccessorOf(after);
        sessions.endSuccessorOf(UUID.randomUUID().toString());
        assertThat(tab.get("/api/me").status()).isEqualTo(200);

        sessions.endSuccessorOf(before);
        assertThat(count("select count(*) from spring_session where principal_name is not null")).isZero();
        assertThat(tab.get("/api/me").status()).isEqualTo(401);
    }

    // ------------------------------------------------------------------------------------
    // D4
    // ------------------------------------------------------------------------------------

    /** An error answer in the contract's shape, whoever produced it: JSON, the Error schema, a request identifier, nothing of a framework. */
    private void assertContractError(RawHttp.Answer answer, String what) {
        assertThat(answer.header("Content-Type")).as(what + " -> " + answer.status() + " " + answer.body()).isNotNull().startsWith("application/json");
        assertThat(answer.body()).as(what).doesNotContain("<").doesNotContain("timestamp").doesNotContain("\"path\"").doesNotContain("\"status\"")
                .doesNotContain("Exception").doesNotContain("Tomcat");
        assertThat(contract.violations("Error", answer.body())).as(what + " -> " + answer.body()).isEmpty();
        String requestId = org.apvgakasinay.support.Json.parse(answer.body()).at("/error/requestId").asText();
        assertThat(requestId).as(what).matches("[0-9a-f]{16}");
        assertThat(answer.header("X-Request-Id")).as(what).isEqualTo(requestId);
    }

    private static String code(RawHttp.Answer answer) {
        return org.apvgakasinay.support.Json.parse(answer.body()).at("/error/code").asText();
    }

    /**
     * D4. Paths the embedded server refuses were answered with its HTML page, and paths the
     * security firewall refuses with the framework's body. The paths of the report, for GET
     * and POST: 400 validation_error in the contract's shape.
     */
    @Test
    void a9_10_encodedAndMalformedPathsAreAnsweredInTheContractShape() {
        List<String> reported = List.of("/api/auth/login%2F", "/api/auth/csrf%00", "/api/auth/login;x=1", "/api//auth/login", "/api/auth/../auth/login");
        for (String path : reported) {
            for (String method : List.of("GET", "POST")) {
                RawHttp.Answer answer = method.equals("POST")
                        ? RawHttp.send(port, "POST " + path, "Content-Type: application/json", "Content-Length: 0")
                        : RawHttp.send(port, "GET " + path);
                String what = method + " " + path;
                assertContractError(answer, what);
                assertThat(answer.status()).as(what).isEqualTo(400);
                assertThat(code(answer)).as(what).isEqualTo("validation_error");
            }
        }
        // More of the same kind. Whatever each is answered, it is an error in the contract's shape and never a success.
        for (String path : List.of("/api/me%2F", "/api/me%2f..", "/api/me;jsessionid=1", "/api/./me", "/api/me/.", "/api/%2e%2e/api/me", "/api/me%5C",
                "/api/me%3B", "/api/cases//", "/api/auth/login%", "/api/auth/login%zz", "//api/me", "/api/me%0d%0a", "/error", "/api/error")) {
            RawHttp.Answer answer = RawHttp.send(port, "GET " + path);
            assertContractError(answer, "GET " + path);
            assertThat(answer.status()).as("GET " + path).isIn(400, 404, 405);
        }
    }

    /**
     * D4. TRACE was answered by the server itself: an empty 405 whose Allow named methods this
     * API does not have. It is now answered by the first step of the request order like any
     * other method, and so is a method nobody has heard of. Nothing of the request is echoed.
     */
    @Test
    void a11_1_and_11_2_traceAndUnknownMethodsAreAnsweredByTheApiItself() {
        Map<String, String> allowed = Map.of("/api/me", "GET, HEAD", "/api/auth/login", "POST", "/api/cases", "GET, HEAD", "/api/auth/csrf", "GET, HEAD");
        for (String method : List.of("TRACE", "BREW", "PUT", "DELETE", "PATCH")) {
            for (Map.Entry<String, String> entry : allowed.entrySet()) {
                String what = method + " " + entry.getKey();
                RawHttp.Answer answer = RawHttp.send(port, what, "X-Echo-Probe: marker-echo-probe", "Cookie: apv_session=marker-echo-cookie");
                assertContractError(answer, what);
                assertThat(answer.status()).as(what).isEqualTo(405);
                assertThat(code(answer)).as(what).isEqualTo("method_not_allowed");
                assertThat(answer.header("Allow")).as(what).isEqualToIgnoringCase(entry.getValue());
                assertThat(answer.body() + answer.head()).as(what).doesNotContain("marker-echo");
                assertThat(answer.header("Set-Cookie")).as(what).isNull();
            }
            RawHttp.Answer unknown = RawHttp.send(port, method + " /api/no-such-path");
            assertContractError(unknown, method + " /api/no-such-path");
            assertThat(unknown.status()).isEqualTo(404);
            assertThat(code(unknown)).isEqualTo("not_found");
        }
    }

    /**
     * D4. An exception raised inside the security filters never reaches the controller advice
     * and was answered with the framework's body. Forced the way the review did it: the
     * account table cannot be read while a signed-in session asks.
     */
    @Test
    void a9_10_anErrorInsideTheSecurityFiltersIsTheGeneric500WithARequestIdentifier() {
        Browser donor = donor("sample.donor@example.org");
        Browser.Answer me;
        Browser.Answer post;
        RawHttp.Answer raw;
        jdbc.sql("alter table account rename to account_unreadable").update();
        try {
            me = donor.get("/api/me");
            post = donor.post("/api/auth/change-password", json("currentPassword", PASSWORD, "newPassword", OTHER_PASSWORD));
            raw = RawHttp.send(port, "GET /api/me", "Cookie: apv_session=" + donor.sessionId);
        } finally {
            jdbc.sql("alter table account_unreadable rename to account").update();
        }
        for (Browser.Answer answer : List.of(me, post)) {
            assertError(answer, 500, "internal_error");
            assertThat(answer.json().at("/error/requestId").asText()).matches("[0-9a-f]{16}").isEqualTo(answer.header("X-Request-Id"));
            assertThat(answer.header("Content-Type")).startsWith("application/json");
            assertThat(answer.body()).doesNotContain("timestamp").doesNotContain("\"path\"").doesNotContain("account").doesNotContain("Exception")
                    .doesNotContain("SQL");
            assertThat(answer.header("X-Content-Type-Options")).isEqualTo("nosniff");
        }
        assertContractError(raw, "GET /api/me with the account table unreadable");
        assertThat(raw.status()).isEqualTo(500);
        // Nothing was changed by the failures: same session, same password.
        assertThat(donor.get("/api/me").status()).isEqualTo(200);
        assertThat(donor.postEmpty("/api/auth/logout").status()).isEqualTo(204);
        assertThat(browser().login("sample.donor@example.org", PASSWORD).status()).isEqualTo(200);
    }

    /** D4. A forwarded-for header with no entry, arriving from the trusted proxy address, was answered 500. */
    @Test
    void a8_8_aForwardedHeaderWithNoEntryIsNotAnError() {
        for (String header : List.of(",", ",,", " , ", "198.51.100.7,", ";")) {
            RawHttp.Answer options = RawHttp.send(port, "GET /api/auth/options", "X-Forwarded-For: " + header);
            assertThat(options.status()).as("X-Forwarded-For: [%s] -> %s", header, options.body()).isEqualTo(200);
            RawHttp.Answer post = RawHttp.send(port, "POST /api/auth/logout", "X-Forwarded-For: " + header, "Content-Length: 0");
            assertThat(post.status()).as("X-Forwarded-For: [%s] -> %s", header, post.body()).isEqualTo(403);
            assertContractError(post, "POST with X-Forwarded-For: " + header);
        }
        // With no entry to believe, the address is the connection's: the proxy's own.
        Browser browser = browser();
        browser.address = ",";
        browser.csrf();
        browser.post("/api/auth/login", json("email", "nobody@example.org", "password", PASSWORD));
        assertThat(jdbc.sql("select client_address from audit_event where event_type = 'session.sign_in'").query(String.class).single()).isEqualTo("127.0.0.1");
    }

    // ------------------------------------------------------------------------------------
    // D5, on a disposable database. The real-database half is in RealDatabaseIT.
    // ------------------------------------------------------------------------------------

    /**
     * D5. TRUNCATE passed by every row trigger, so one statement emptied the audit log. On a
     * disposable database it still resets, which is what the fixture loader and these tests
     * use it for. The moment the database is real it is refused, for every protected table,
     * whatever else the statement names, and whether or not the disposable marker is there.
     */
    @Test
    void a12_2_truncateResetsADisposableDatabaseAndIsRefusedOnceItIsReal() {
        donor("sample.donor@example.org");
        assertThat(count("select count(*) from audit_event")).isPositive();
        jdbc.sql("truncate table audit_event").update();
        assertThat(count("select count(*) from audit_event")).isZero();

        donor("other.donor@example.org");
        long audit = count("select count(*) from audit_event");
        long accounts = count("select count(*) from account");
        assertThat(audit).isPositive();
        // What the operator command does the first time it is used. One-way.
        jdbc.sql("update identity_guard set database_real = true").update();
        for (String statement : List.of(
                "truncate table audit_event",
                "truncate audit_event restart identity cascade",
                "truncate table ledger_entry cascade",
                "truncate table aid_case cascade",
                "truncate table ledger_platform_total",
                "truncate table identity_guard",
                "truncate table account cascade",
                "truncate table account_totp",
                "truncate table account_password, account_totp",
                "truncate table spring_session_retired",
                "truncate table spring_session cascade",
                "truncate table auth_throttle, audit_event")) {
            assertThatThrownBy(() -> jdbc.sql(statement).update()).as(statement)
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("never truncated on a real database");
        }
        assertThat(count("select count(*) from audit_event")).isEqualTo(audit);
        assertThat(count("select count(*) from account")).isEqualTo(accounts);
        assertThat(jdbc.sql("select database_real from identity_guard").query(Boolean.class).single()).isTrue();
        // Tables with no such rule are not affected.
        jdbc.sql("truncate table auth_throttle").update();
    }
}
