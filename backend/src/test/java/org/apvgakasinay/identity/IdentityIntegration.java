package org.apvgakasinay.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.apvgakasinay.identity.internal.StaffAdministration;
import org.apvgakasinay.identity.internal.Totp;
import org.apvgakasinay.shared.AppClock;
import org.apvgakasinay.support.Contract;
import org.apvgakasinay.support.SharedPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base of the identity tests: the whole application on a real port, a real PostgreSQL started
 * by Testcontainers, and real HTTP with cookies handled the way a browser handles them.
 *
 * Test names start with the identifier of the scenario they cover in
 * contract/tests/security-acceptance.md, for example {@code a3_14_...} for scenario 3.14.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
abstract class IdentityIntegration {

    static final String PASSWORD = "a long harmless passphrase 2026";
    static final String OTHER_PASSWORD = "another harmless passphrase 2027";
    static final Instant START = Instant.parse("2026-11-24T12:00:00Z");
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    AppClock clock;

    @Autowired
    StaffAdministration staffAdministration;

    final Contract contract = Contract.get();

    /** A staff member with an active second factor, and the browser they are signed in with. */
    record Staff(String email, Browser browser, byte[] secret, List<String> recoveryCodes) {
    }

    @BeforeEach
    void cleanIdentityData() {
        // TRUNCATE is how a disposable database is reset; it is the one thing the append-only and one-way rules do not see.
        jdbc.sql("""
                truncate table spring_session_retired, spring_session_attributes, spring_session, pending_sign_in, auth_throttle, audit_event, one_time_credential,
                               account_recovery_code, account_totp, account_password, account, identity_guard
                restart identity cascade
                """).update();
        jdbc.sql("insert into identity_guard default values").update();
        clock.pin(START);
        Browser.RECORDED.clear();
    }

    Browser browser() {
        return new Browser(port);
    }

    /** Runs the calls at the same moment, each on its own thread, and returns their results in order. */
    static <T> List<T> together(List<java.util.concurrent.Callable<T>> calls) {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(calls.size());
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<T>> futures = new ArrayList<>();
            for (java.util.concurrent.Callable<T> call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (java.util.concurrent.Future<T> future : futures) {
                results.add(future.get(60, java.util.concurrent.TimeUnit.SECONDS));
            }
            return results;
        } catch (Exception failure) {
            throw new AssertionError(failure);
        } finally {
            pool.shutdownNow();
        }
    }

    /** A second handle on the same browser session: same cookie, same token, as another tab's request would carry. */
    static Browser sameSession(Browser browser) {
        Browser copy = browser.copyOfCookie();
        copy.token = browser.token;
        copy.address = browser.address;
        return copy;
    }

    private static final java.util.Map<String, String> SUCCESS_SCHEMAS = java.util.Map.of(
            "GET /api/me", "Account",
            "GET /api/auth/csrf", "CsrfToken",
            "GET /api/auth/options", "AuthOptions",
            "POST /api/auth/register", "RegistrationAccepted",
            "POST /api/auth/login", "LoginResult",
            "POST /api/auth/mfa/verify", "SignedIn",
            "POST /api/auth/mfa/enroll", "MfaEnrollment",
            "POST /api/auth/mfa/confirm", "MfaRecoveryCodes",
            "POST /api/auth/mfa/recovery-codes", "MfaRecoveryCodes");

    /**
     * Scenarios 9.1 and 9.9, applied to everything every test did: each identity answer is
     * never stored, and its body is valid against the closed schema the contract gives it.
     */
    @org.junit.jupiter.api.AfterEach
    void everyIdentityAnswerMatchesTheContract() {
        for (Browser.Answer answer : List.copyOf(Browser.RECORDED)) {
            String path = answer.path().replaceAll("\\?.*", "");
            if (!path.equals("/api/me") && !path.startsWith("/api/auth/")) {
                continue;
            }
            String what = answer.method() + " " + path + " -> " + answer.status() + " " + answer.body();
            assertThat(answer.header("Cache-Control")).as(what).isEqualTo("no-store");
            assertThat(answer.header("ETag")).as(what).isNull();
            if (answer.status() >= 400) {
                assertThat(contract.violations("Error", answer.body())).as(what).isEmpty();
            } else if (answer.status() == 204) {
                assertThat(answer.body()).as(what).isEmpty();
            } else {
                String schema = SUCCESS_SCHEMAS.get(answer.method() + " " + path);
                assertThat(schema).as("no schema known for " + what).isNotNull();
                assertThat(contract.violations(schema, answer.body())).as(what).isEmpty();
            }
        }
    }

    /** Moves the application clock forward. */
    void advance(Duration duration) {
        clock.pin(clock.now().plus(duration));
    }

    /** Moves to the next authenticator time step, so a new code can be accepted. */
    void nextCode() {
        advance(Duration.ofSeconds(Totp.STEP_SECONDS));
    }

    static String json(String... pairs) {
        StringBuilder out = new StringBuilder("{");
        for (int i = 0; i < pairs.length; i += 2) {
            out.append(i == 0 ? "" : ",").append('"').append(pairs[i]).append("\":\"")
                    .append(pairs[i + 1].replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return out.append('}').toString();
    }

    Browser.Answer register(Browser browser, String email, String password) {
        browser.csrf();
        return browser.post("/api/auth/register", json("email", email, "password", password, "displayName", "Sample Person"));
    }

    /** A donor who signed up and is signed in. */
    Browser donor(String email) {
        Browser browser = browser();
        assertThat(register(browser, email, PASSWORD).status()).isEqualTo(202);
        Browser.Answer login = browser.login(email, PASSWORD);
        assertThat(login.status()).as(login.body()).isEqualTo(200);
        return browser;
    }

    String code(byte[] secret) {
        return Totp.code(secret, Totp.step(clock.now()));
    }

    static byte[] base32Decode(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : text.toCharArray()) {
            buffer = (buffer << 5) | BASE32.indexOf(c);
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    /** Creates a staff account the way the operator does, and returns its one-time activation credential. */
    String createStaff(String email, String role) {
        return staffAdministration.createStaff(email, "Sample Staff", role, "test-operator");
    }

    /** A staff account that has chosen its password with its credential, and nothing more. */
    Browser activatedStaff(String email, String role) {
        String credential = createStaff(email, role);
        Browser browser = browser();
        browser.csrf();
        Browser.Answer set = browser.post("/api/auth/set-password", json("credential", credential, "newPassword", PASSWORD));
        assertThat(set.status()).as(set.body()).isEqualTo(204);
        return browser;
    }

    /** A staff member who is activated, enrolled and fully signed in. */
    Staff staff(String email, String role) {
        Browser browser = activatedStaff(email, role);
        Browser.Answer login = browser.login(email, PASSWORD);
        assertThat(login.json().get("status").asText()).as(login.body()).isEqualTo("mfa_enrollment_required");
        Browser.Answer enrollment = browser.postEmpty("/api/auth/mfa/enroll");
        assertThat(enrollment.status()).as(enrollment.body()).isEqualTo(200);
        byte[] secret = base32Decode(enrollment.json().get("secret").asText());
        Browser.Answer confirmed = browser.post("/api/auth/mfa/confirm", json("code", code(secret)));
        assertThat(confirmed.status()).as(confirmed.body()).isEqualTo(200);
        List<String> codes = new ArrayList<>();
        confirmed.json().get("codes").forEach(node -> codes.add(node.asText()));
        browser.csrf();
        // The code just used cannot be used again; later sign-ins need the next time step.
        nextCode();
        return new Staff(email, browser, secret, codes);
    }

    /** A second browser of an enrolled staff member, with the password accepted and the second factor still owed. */
    Browser pending(Staff staff) {
        Browser browser = browser();
        Browser.Answer login = browser.login(staff.email(), PASSWORD);
        assertThat(login.json().get("status").asText()).as(login.body()).isEqualTo("mfa_required");
        return browser;
    }

    /** Makes a stored session look as if nothing had touched it for the given time. The row stays. */
    void lastUsed(Browser browser, Duration ago) {
        int updated = jdbc.sql("""
                        update spring_session
                           set last_access_time = :lastAccess, expiry_time = :lastAccess + max_inactive_interval * 1000
                         where session_id = :id
                        """)
                .param("lastAccess", System.currentTimeMillis() - ago.toMillis())
                .param("id", browser.storedSessionId())
                .update();
        assertThat(updated).isEqualTo(1);
    }

    long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    /** Asserts a response body is valid against a named schema of contract/openapi.yaml. */
    void assertValid(String schema, Browser.Answer answer) {
        assertThat(contract.violations(schema, answer.body())).as("%s %s -> %s", answer.method(), answer.path(), answer.body()).isEmpty();
    }

    /** Asserts an error answer: status, code, the contract's envelope, never cached. */
    void assertError(Browser.Answer answer, int status, String code) {
        assertThat(answer.status()).as("%s %s -> %s", answer.method(), answer.path(), answer.body()).isEqualTo(status);
        assertThat(answer.code()).as(answer.body()).isEqualTo(code);
        assertValid("Error", answer);
        assertThat(answer.header("Cache-Control")).isEqualTo("no-store");
    }
}
