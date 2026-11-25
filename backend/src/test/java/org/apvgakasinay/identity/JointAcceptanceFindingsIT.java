package org.apvgakasinay.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Regression tests for the backend defects the first real-browser run found
 * (docs/m1b-acceptance-evidence.md of 2026-11-25, findings 2, 3 and 4). Finding 5 needs the
 * dev profile and is in DevProfileIdentityClockIT.
 */
@ExtendWith(OutputCaptureExtension.class)
class JointAcceptanceFindingsIT extends IdentityIntegration {

    private static final List<String> POSTS_WITH_BODY = List.of("/api/auth/register", "/api/auth/login", "/api/auth/change-password",
            "/api/auth/set-password", "/api/auth/mfa/verify", "/api/auth/mfa/confirm", "/api/auth/mfa/recovery-codes");
    private static final List<String> POSTS_WITHOUT_BODY = List.of("/api/auth/logout", "/api/auth/mfa/enroll");
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    private static final int TOKENS_PER_OPERATION = 300;

    private final SecureRandom random = new SecureRandom();

    /** The genuine token with one to four characters replaced by different ones: what finding 2 sent. */
    private String altered(String genuine) {
        char[] sent = genuine.toCharArray();
        for (int k = 1 + random.nextInt(4); k > 0; k--) {
            int at = random.nextInt(sent.length);
            char other;
            do {
                other = ALPHABET.charAt(random.nextInt(64));
            } while (other == genuine.charAt(at));
            sent[at] = other;
        }
        return new String(sent);
    }

    private String randomOfTheSameShape(String genuine) {
        StringBuilder sent = new StringBuilder();
        for (int i = 0; i < genuine.length(); i++) {
            sent.append(ALPHABET.charAt(random.nextInt(64)));
        }
        return sent.toString();
    }

    /**
     * Finding 2. A token of the right length and alphabet with wrong content was answered 500
     * with no body. Every such value, on every POST, on an anonymous and on a signed-in
     * session, is 403 csrf_invalid with the contract's Error body (checked for every recorded
     * answer by the base class), and nothing is done.
     */
    @Test
    void a4_3_anyTokenOfValidShapeIsRefused403OnEveryPost() {
        Browser donor = donor("sample.donor@example.org");
        Browser anonymous = browser();
        anonymous.csrf();
        assertThat(anonymous.token).hasSize(donor.token.length()).matches("[A-Za-z0-9_-]+");
        long accounts = count("select count(*) from account");
        long sessions = count("select count(*) from spring_session");
        long audit = count("select count(*) from audit_event");
        long throttle = count("select coalesce(sum(attempts), 0) from auth_throttle where scope not like 'token%'");

        List<String> paths = new ArrayList<>(POSTS_WITH_BODY);
        paths.addAll(POSTS_WITHOUT_BODY);
        Map<String, Integer> unexpected = new TreeMap<>();
        int sent = 0;
        for (String path : paths) {
            boolean body = POSTS_WITH_BODY.contains(path);
            for (int i = 0; i < TOKENS_PER_OPERATION; i++) {
                Browser browser = i % 3 == 0 ? donor : anonymous;
                String token = i % 2 == 0 ? altered(browser.token) : randomOfTheSameShape(browser.token);
                Browser.Answer answer = browser.send("POST", path, body ? "{}" : null, body ? "application/json" : null, token);
                sent++;
                if (answer.status() != 403 || !"csrf_invalid".equals(answer.code())) {
                    // The token never goes into a failure message.
                    unexpected.merge(path + " -> " + answer.status() + " " + answer.code(), 1, Integer::sum);
                }
            }
        }
        assertThat(sent).isEqualTo(9 * TOKENS_PER_OPERATION);
        assertThat(unexpected).as("answers other than 403 csrf_invalid to a token of valid shape").isEmpty();

        // Refused at step 4: no account, session, audit event or rate-limit counter moved, and both sessions still work.
        assertThat(count("select count(*) from account")).isEqualTo(accounts);
        assertThat(count("select count(*) from spring_session")).isEqualTo(sessions);
        assertThat(count("select count(*) from audit_event")).isEqualTo(audit);
        assertThat(count("select coalesce(sum(attempts), 0) from auth_throttle where scope not like 'token%'")).isEqualTo(throttle);
        assertThat(donor.get("/api/me").status()).isEqualTo(200);
        assertThat(donor.postEmpty("/api/auth/logout").status()).isEqualTo(204);
        assertThat(anonymous.postEmpty("/api/auth/logout").status()).isEqualTo(204);
        Evidence.write("csrf-tokens-of-valid-shape", sent + " POSTs with a random or altered token of valid shape across 9 operations, "
                + "anonymous and signed-in: all 403 csrf_invalid, 0 other answers");
    }

    /** What the session holds, from the masked form the endpoint gives out (a new mask at every read). */
    private static String unmasked(String token) {
        byte[] bytes = Base64.getUrlDecoder().decode(token);
        int half = bytes.length / 2;
        byte[] out = new byte[half];
        for (int i = 0; i < half; i++) {
            out[i] = (byte) (bytes[i] ^ bytes[half + i]);
        }
        return new String(out, StandardCharsets.UTF_8);
    }

    private long storedTokens(Browser browser) {
        return jdbc.sql("""
                        select count(*) from spring_session_attributes a join spring_session s on s.primary_id = a.session_primary_id
                         where s.session_id = :id and a.attribute_name like '%CSRF_TOKEN'
                        """)
                .param("id", browser.storedSessionId()).query(Long.class).single();
    }

    /**
     * Many requests ask for the token of a session at the same moment, before anyone has asked.
     * All are answered 200 and all hold the same token. Leaves that token in the browser.
     */
    private int readTogether(Browser browser, int readers) {
        // The token is stored with the session change itself, before it is first asked for.
        assertThat(storedTokens(browser)).isEqualTo(1);
        List<Callable<Browser.Answer>> reads = new ArrayList<>();
        for (int i = 0; i < readers; i++) {
            Browser tab = sameSession(browser);
            reads.add(() -> tab.get("/api/auth/csrf"));
        }
        List<Browser.Answer> answers = together(reads);
        Set<String> tokens = new HashSet<>();
        int failures = 0;
        for (Browser.Answer answer : answers) {
            if (answer.status() == 200) {
                tokens.add(unmasked(answer.json().get("token").asText()));
            } else {
                failures++;
            }
        }
        assertThat(failures).as("token reads that were not answered 200").isZero();
        assertThat(tokens.size()).as("distinct tokens given out for one session").isEqualTo(1);
        assertThat(storedTokens(browser)).isEqualTo(1);
        browser.token = answers.get(0).json().get("token").asText();
        return answers.size();
    }

    private static String totp(String code) {
        return "{\"method\":\"totp\",\"code\":\"" + code + "\"}";
    }

    /**
     * Finding 3. Right after sign-in the session had no stored token; two simultaneous first
     * reads both tried to store one, and one was answered 500. Repeated here after each of the
     * three session changes: sign-in, second factor, password change.
     */
    @Test
    void a4_1_simultaneousFirstTokenReadsAfterASessionChangeGiveOneTokenAndNoError() {
        String email = "sample.donor@example.org";
        assertThat(register(browser(), email, PASSWORD).status()).isEqualTo(202);
        int reads = 0;
        int rounds = 0;
        Browser last = null;
        for (int round = 0; round < 12; round++) {
            Browser browser = browser();
            browser.csrf();
            // Not Browser.login: that one reads a new token by itself afterwards.
            assertThat(browser.post("/api/auth/login", json("email", email, "password", PASSWORD)).status()).isEqualTo(200);
            reads += readTogether(browser, 12);
            rounds++;
            // The token everyone got is the session's: it is accepted.
            if (round < 11) {
                assertThat(browser.postEmpty("/api/auth/logout").status()).isEqualTo(204);
            }
            last = browser;
        }
        String current = PASSWORD;
        for (int round = 0; round < 3; round++) {
            String next = current.equals(PASSWORD) ? OTHER_PASSWORD : PASSWORD;
            assertThat(last.post("/api/auth/change-password", json("currentPassword", current, "newPassword", next)).status()).isEqualTo(204);
            current = next;
            reads += readTogether(last, 12);
            rounds++;
        }
        assertThat(last.get("/api/me").status()).isEqualTo(200);

        Staff owner = staff("sample.owner@example.org", "super_admin");
        for (int round = 0; round < 4; round++) {
            Browser browser = browser();
            browser.csrf();
            Browser.Answer login = browser.post("/api/auth/login", json("email", owner.email(), "password", PASSWORD));
            assertThat(login.json().get("status").asText()).as(login.body()).isEqualTo("mfa_required");
            reads += readTogether(browser, 12);
            Browser.Answer verified = browser.post("/api/auth/mfa/verify", totp(code(owner.secret())));
            assertThat(verified.status()).as(verified.body()).isEqualTo(200);
            reads += readTogether(browser, 12);
            rounds += 2;
            assertThat(browser.get("/api/me").status()).isEqualTo(200);
            nextCode();
        }
        Evidence.write("simultaneous-first-token-reads", rounds + " rounds of 12 simultaneous first reads after sign-in, password change, "
                + "pending sign-in and second factor: " + reads + " reads, all 200, one token per session, 0 errors");
    }

    private static String everyMessage(Throwable thrown) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            all.append(t.getMessage()).append('\n');
        }
        return all.toString();
    }

    /**
     * Finding 4. The database's own error text quotes the row it refused, and that reached the
     * log through the stack trace. Two real errors from PostgreSQL, one naming a session row's
     * key and one naming the session identifier itself, are logged the way the application
     * logs a failure; neither value, nor the cookie form, is in the output.
     */
    @Test
    void a9_8_aRealDatabaseErrorThatNamesASessionIsLoggedWithoutItsIdentifier(CapturedOutput output) {
        Browser donor = donor("sample.donor@example.org");
        String stored = donor.storedSessionId();
        String cookie = donor.sessionId;
        String primary = jdbc.sql("select primary_id from spring_session where session_id = :id").param("id", stored).query(String.class).single();

        Throwable attribute = catchThrowable(() -> jdbc.sql("""
                insert into spring_session_attributes (session_primary_id, attribute_name, attribute_bytes)
                select session_primary_id, attribute_name, attribute_bytes from spring_session_attributes
                 where session_primary_id = :primary fetch first 1 row only
                """).param("primary", primary).update());
        Throwable identifier = catchThrowable(() -> jdbc.sql("""
                insert into spring_session (primary_id, session_id, creation_time, last_access_time, max_inactive_interval, expiry_time)
                select :other, session_id, creation_time, last_access_time, max_inactive_interval, expiry_time
                  from spring_session where session_id = :id
                """).param("other", UUID.randomUUID().toString()).param("id", stored).update());
        // The test is only worth something if the database really quotes the values.
        assertThat(attribute != null && everyMessage(attribute).contains(primary)).as("the first error quotes the session row key").isTrue();
        assertThat(identifier != null && everyMessage(identifier).contains(stored)).as("the second error quotes the session identifier").isTrue();

        org.slf4j.Logger log = LoggerFactory.getLogger(JointAcceptanceFindingsIT.class);
        log.error("marker-attribute: could not save the session", attribute);
        log.error("marker-identifier: could not save the session", identifier);
        log.warn("marker-wrapped: request {} failed", "abc", new IllegalStateException("Request processing failed for cookie " + cookie, identifier));

        String printed = output.getAll();
        assertThat(printed).contains("marker-attribute").contains("marker-identifier").contains("marker-wrapped: request abc failed")
                .contains("[redacted-id]").contains("DuplicateKeyException");
        boolean found = printed.contains(stored) || printed.contains(primary) || printed.contains(cookie);
        assertThat(found).as("a session identifier, a session row key or a cookie value reached the log inside an exception").isFalse();
        // The session is as it was.
        assertThat(donor.get("/api/me").status()).isEqualTo(200);
    }
}
