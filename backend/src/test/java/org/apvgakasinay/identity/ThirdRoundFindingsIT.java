package org.apvgakasinay.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Regression tests for the third independent round (7aa1679, 2026-11-25).
 *
 * R2: a sign-out carrying two values of the session cookie ended the successor of the first
 * value, a session the request had never resolved and whose token it had never shown.
 * R3: a session cookie that decodes to text the database cannot look up answered 500.
 */
@ExtendWith(OutputCaptureExtension.class)
class ThirdRoundFindingsIT extends IdentityIntegration {

    private static String encoded(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    /** A request of this browser's session, with the Cookie header written out by the test. */
    private Browser withCookies(Browser session, String... values) {
        Browser copy = sameSession(session);
        // The browser writes "name=" before this text itself.
        copy.sessionId = String.join("; " + copy.cookieName + "=", values);
        return copy;
    }

    private long stored(String encodedIdentifier) {
        return jdbc.sql("select count(*) from spring_session where session_id = :id")
                .param("id", new String(Base64.getDecoder().decode(encodedIdentifier), StandardCharsets.UTF_8)).query(Long.class).single();
    }

    /**
     * R2. The victim signs in, which replaces the identifier of their session: V0 is retired,
     * V1 is live. A caller with their own session and their own token sends a sign-out whose
     * Cookie header holds V0 as well. Whatever the order of the two values, and whether the
     * caller is anonymous or signed in, the sign-out ends the caller's session and nothing of
     * the victim's.
     */
    @Test
    void a3_6_aSignOutNeverEndsASessionNamedOnlyByAnotherCookieValue() {
        String email = "sample.donor@example.org";
        assertThat(register(browser(), email, PASSWORD).status()).isEqualTo(202);
        Browser victim = browser();
        victim.csrf();
        String retired = victim.sessionId;
        assertThat(victim.login(email, PASSWORD).status()).isEqualTo(200);
        String live = victim.sessionId;
        assertThat(live).isNotEqualTo(retired);

        Map<String, Integer> answers = new LinkedHashMap<>();
        for (boolean signedIn : List.of(false, true)) {
            for (boolean retiredFirst : List.of(true, false)) {
                Browser caller = signedIn ? donor("caller." + retiredFirst + "@example.org") : browser();
                caller.csrf();
                String own = caller.sessionId;
                Browser forged = retiredFirst ? withCookies(caller, retired, own) : withCookies(caller, own, retired);
                String what = (signedIn ? "signed-in" : "anonymous") + " caller, " + (retiredFirst ? "retired value first" : "own value first");

                Browser.Answer signOut = forged.postEmpty("/api/auth/logout");
                answers.put(what, signOut.status());

                assertThat(victim.get("/api/me").status()).as(what + ": the victim is still signed in").isEqualTo(200);
                assertThat(stored(live)).as(what).isEqualTo(1);
                assertThat(signOut.status()).as(what + " -> " + signOut.body()).isEqualTo(204);
                assertThat(stored(own)).as(what + ": the caller's own session is ended").isZero();
            }
        }

        // The victim's live identifier first, with the caller's token: that is the victim's
        // session, the token is not its token, and the token check refuses.
        Browser caller = browser();
        caller.csrf();
        Browser forged = withCookies(caller, live, caller.sessionId);
        assertError(forged.postEmpty("/api/auth/logout"), 403, "csrf_invalid");
        assertThat(victim.get("/api/me").status()).isEqualTo(200);
        // And a retired identifier alone names no session at all.
        assertError(withCookies(caller, retired).postEmpty("/api/auth/logout"), 403, "csrf_invalid");
        assertThat(victim.get("/api/me").status()).isEqualTo(200);

        // The victim's own sign-out still works.
        assertThat(victim.postEmpty("/api/auth/logout").status()).isEqualTo(204);
        assertThat(stored(live)).isZero();
        Evidence.write("sign-out-with-two-session-cookies", answers + "; the other session stayed signed in every time");
    }

    /**
     * R3. A session cookie that is not an identifier this server could have issued (a null
     * byte, control characters, the wrong length, not base64) is a cookie that names no
     * session: the same answers as for an unknown identifier, never 500, nothing logged as an error.
     */
    @Test
    void a9_10_aSessionCookieThatIsNotAnIdentifierIsAnUnknownCookie(CapturedOutput output) {
        String unknown = encoded(UUID.randomUUID().toString());
        String withNull = UUID.randomUUID().toString().substring(0, 35) + "\u0000";
        List<String> malformed = List.of(
                "AA==",
                encoded("abc\u0000def"),
                encoded(withNull),
                encoded("\u0000" + UUID.randomUUID()),
                encoded("\u0001\u0002\u0003"),
                encoded("x".repeat(500)),
                encoded("' or 1=1 --"),
                "AA==; apv_session=AAAA");
        Browser tokenHolder = browser();
        String token = tokenHolder.csrf();
        int before = output.getAll().length();

        for (String path : List.of("/api/site", "/api/cases", "/api/me", "/api/auth/csrf", "/api/auth/options")) {
            Browser reference = browser();
            reference.sessionId = unknown;
            int expected = reference.get(path).status();
            assertThat(expected).as(path).isLessThan(500);
            for (String value : malformed) {
                Browser browser = browser();
                browser.sessionId = value;
                Browser.Answer answer = browser.get(path);
                assertThat(answer.status()).as("GET %s with cookie %s -> %s", path, value, answer.body()).isEqualTo(expected);
            }
        }
        for (String value : malformed) {
            Browser browser = browser();
            browser.sessionId = value;
            assertError(browser.send("POST", "/api/auth/logout", null, null, token), 403, "csrf_invalid");
            // A malformed value in front of a real one does not hide the real one.
            Browser signedIn = donor("donor." + malformed.indexOf(value) + "@example.org");
            signedIn.sessionId = value + "; apv_session=" + signedIn.sessionId;
            assertThat(signedIn.get("/api/me").status()).as(value).isEqualTo(200);
        }
        assertThat(output.getAll().substring(before)).doesNotContain(" ERROR ").doesNotContain("Exception");
    }
}
