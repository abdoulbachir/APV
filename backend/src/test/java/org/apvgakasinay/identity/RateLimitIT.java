package org.apvgakasinay.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Security acceptance group 8: rate limits, counted in PostgreSQL before any secret is checked. */
class RateLimitIT extends IdentityIntegration {

    private static final String EMAIL = "sample.donor@example.org";

    private Browser.Answer wrong(Browser browser, String email) {
        browser.csrf();
        return browser.post("/api/auth/login", json("email", email, "password", OTHER_PASSWORD));
    }

    /** 8.6: every 429 of every test here carries a bounded Retry-After. */
    @AfterEach
    void everyRefusalSaysWhenToComeBack() {
        for (Browser.Answer answer : List.copyOf(Browser.RECORDED)) {
            if (answer.status() == 429) {
                assertThat(answer.code()).isEqualTo("rate_limited");
                assertThat(answer.header("Cache-Control")).isEqualTo("no-store");
                long wait = Long.parseLong(answer.header("Retry-After"));
                assertThat(wait).as(answer.path()).isBetween(1L, answer.path().equals("/api/auth/register") ? 3600L : 900L);
            }
        }
    }

    @Test
    void a8_1_a8_2_a8_4_theSixthFailureFromOneAddressIsRefusedAndOnlyThere() {
        register(browser(), EMAIL, PASSWORD);
        Browser attacker = browser();
        for (int i = 1; i <= 5; i++) {
            assertError(wrong(attacker, EMAIL), 401, "invalid_credentials");
        }
        Browser.Answer sixth = wrong(attacker, EMAIL);
        assertError(sixth, 429, "rate_limited");
        // The correct password is refused too, until the window ends.
        assertError(attacker.login(EMAIL, PASSWORD), 429, "rate_limited");

        // 8.4: an unknown address is throttled the same way, with the same answer.
        Browser other = browser();
        for (int i = 1; i <= 5; i++) {
            assertError(wrong(other, "nobody@example.org"), 401, "invalid_credentials");
        }
        Browser.Answer unknown = wrong(other, "nobody@example.org");
        assertError(unknown, 429, "rate_limited");
        assertThat(unknown.body().replaceAll("\"requestId\":\"[0-9a-f]+\"", "")).isEqualTo(sixth.body().replaceAll("\"requestId\":\"[0-9a-f]+\"", ""));

        // 8.2: the person, from their own address, is not locked out.
        assertThat(browser().login(EMAIL, PASSWORD).status()).isEqualTo(200);
        // And when the window ends the first address works again.
        advance(Duration.ofMinutes(15));
        assertThat(attacker.login(EMAIL, PASSWORD).status()).isEqualTo(200);
    }

    @Test
    void a8_3_manyAddressesAgainstOneAccountGetABoundedPause() {
        register(browser(), EMAIL, PASSWORD);
        for (int i = 1; i <= 20; i++) {
            assertError(wrong(browser(), EMAIL), 401, "invalid_credentials");
        }
        Browser.Answer refused = wrong(browser(), EMAIL);
        assertError(refused, 429, "rate_limited");
        assertThat(Long.parseLong(refused.header("Retry-After"))).isEqualTo(900);
        assertError(browser().login(EMAIL, PASSWORD), 429, "rate_limited");
        // The account is never disabled, and after the pause sign-in works.
        assertThat(count("select count(*) from account where enabled")).isEqualTo(1);
        advance(Duration.ofMinutes(15));
        assertThat(browser().login(EMAIL, PASSWORD).status()).isEqualTo(200);
    }

    @Test
    void a8_5_and_8_12_fiftySimultaneousGuessesVerifyAtMostFivePasswords() throws Exception {
        register(browser(), EMAIL, PASSWORD);
        Browser first = browser();
        List<Browser> browsers = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            Browser browser = browser();
            browser.address = first.address;
            browser.csrf();
            browsers.add(browser);
        }
        ExecutorService pool = Executors.newFixedThreadPool(50);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (Browser browser : browsers) {
                calls.add(() -> browser.post("/api/auth/login", json("email", EMAIL, "password", OTHER_PASSWORD)).status());
            }
            for (Future<Integer> result : pool.invokeAll(calls)) {
                statuses.add(result.get());
            }
        } finally {
            pool.shutdownNow();
        }
        long checked = statuses.stream().filter(status -> status == 401).count();
        assertThat(checked).as("passwords verified: %s", statuses).isBetween(1L, 5L);
        assertThat(statuses.stream().filter(status -> status == 429).count()).isEqualTo(50 - checked);
        // Each verified password left one failure record; no other password was looked at.
        assertThat(count("select count(*) from audit_event where event_type = 'session.sign_in'")).isEqualTo(checked);
    }

    @Test
    void a8_7_signUpCredentialAndTokenLimits() {
        Browser signUps = browser();
        for (int i = 1; i <= 5; i++) {
            assertThat(register(signUps, "person" + i + "@example.org", PASSWORD).status()).isEqualTo(202);
        }
        Browser.Answer sixth = register(signUps, "person6@example.org", PASSWORD);
        assertError(sixth, 429, "rate_limited");
        assertThat(Long.parseLong(sixth.header("Retry-After"))).isLessThanOrEqualTo(3600);
        assertThat(count("select count(*) from account")).isEqualTo(5);

        Browser credentials = browser();
        credentials.csrf();
        String guess = json("credential", "q3Zr8vT1nK5xW0bM7cJ2fH9dL4sP6gY1aE8uR3tV0oI", "newPassword", PASSWORD);
        for (int i = 1; i <= 10; i++) {
            assertError(credentials.post("/api/auth/set-password", guess), 400, "credential_invalid");
        }
        Browser.Answer eleventh = credentials.post("/api/auth/set-password", guess);
        assertError(eleventh, 429, "rate_limited");
        assertThat(eleventh.header("Retry-After")).isEqualTo("900");

        Browser tokens = browser();
        for (int i = 1; i <= 60; i++) {
            assertThat(tokens.get("/api/auth/csrf").status()).isEqualTo(200);
            assertThat(tokens.get("/api/auth/options").status()).isEqualTo(200);
        }
        assertError(tokens.get("/api/auth/csrf"), 429, "rate_limited");
        assertError(tokens.get("/api/auth/options"), 429, "rate_limited");
    }

    @Test
    void a8_9_secretsOfSignedInOperationsDrawOnTheAccountCounters() {
        Staff admin = staff("sample.admin@example.org", "admin");
        Browser browser = admin.browser();
        String path = "/api/auth/mfa/recovery-codes";
        // Wrong authenticator codes on recovery-codes: the second-factor counter, ten an hour.
        for (int i = 1; i <= 10; i++) {
            assertError(browser.post(path, json("currentPassword", PASSWORD, "code", "000000")), 401, "mfa_code_invalid");
        }
        assertError(browser.post(path, json("currentPassword", PASSWORD, "code", code(admin.secret()))), 429, "rate_limited");
        assertThat(browser.get("/api/me").status()).as("refusals about a secret never end the session").isEqualTo(200);

        // Wrong current passwords: the password counter, twenty an hour, shared by both operations.
        Browser donor = donor(EMAIL);
        for (int i = 1; i <= 20; i++) {
            assertError(donor.post("/api/auth/change-password", json("currentPassword", "not the right one at all", "newPassword", OTHER_PASSWORD)), 401, "invalid_credentials");
        }
        assertError(donor.post("/api/auth/change-password", json("currentPassword", PASSWORD, "newPassword", OTHER_PASSWORD)), 429, "rate_limited");
        assertThat(donor.get("/api/me").status()).isEqualTo(200);
        assertThat(browser().login(EMAIL, PASSWORD).status()).as("the password was not changed").isNotEqualTo(401);
    }

    @Test
    void a8_9_wrongCodesAtEnrollmentAndWrongPasswordsOnRecoveryCodesAreCountedToo() {
        Browser enrolling = activatedStaff("new.admin@example.org", "admin");
        enrolling.login("new.admin@example.org", PASSWORD);
        enrolling.postEmpty("/api/auth/mfa/enroll");
        for (int i = 1; i <= 10; i++) {
            assertError(enrolling.post("/api/auth/mfa/confirm", json("code", "000000")), 401, "mfa_code_invalid");
        }
        assertError(enrolling.post("/api/auth/mfa/confirm", json("code", "000000")), 429, "rate_limited");

        Staff admin = staff("sample.admin@example.org", "admin");
        for (int i = 1; i <= 20; i++) {
            assertError(admin.browser().post("/api/auth/mfa/recovery-codes", json("currentPassword", OTHER_PASSWORD, "code", "000000")), 401, "invalid_credentials");
        }
        assertError(admin.browser().post("/api/auth/mfa/recovery-codes", json("currentPassword", PASSWORD, "code", code(admin.secret()))), 429, "rate_limited");
        assertThat(count("select count(*) from account_recovery_code where used_at is null")).isEqualTo(10);
    }

    @Test
    void a8_10_countersHoldHashesNotAddresses() {
        Browser attacker = browser();
        register(attacker, EMAIL, PASSWORD);
        wrong(attacker, EMAIL);
        assertThat(count("select count(*) from auth_throttle")).isGreaterThan(2);
        assertThat(count("select count(*) from auth_throttle where length(key_hash) <> 32")).isZero();
        String all = jdbc.sql("select string_agg(scope || ' ' || encode(key_hash, 'escape'), ' ') from auth_throttle").query(String.class).single();
        assertThat(all).doesNotContain("sample.donor").doesNotContain("example.org").doesNotContain(attacker.address);
    }

    @Test
    void a8_13_aSuccessClearsThePairCounter() {
        register(browser(), EMAIL, PASSWORD);
        Browser browser = browser();
        for (int i = 1; i <= 4; i++) {
            assertError(wrong(browser, EMAIL), 401, "invalid_credentials");
        }
        assertThat(browser.login(EMAIL, PASSWORD).status()).isEqualTo(200);
        browser.csrf();
        assertThat(browser.postEmpty("/api/auth/logout").status()).isEqualTo(204);
        // Five more failures are counted from zero: none of them is a 429.
        for (int i = 1; i <= 5; i++) {
            assertError(wrong(browser, EMAIL), 401, "invalid_credentials");
        }
        assertError(wrong(browser, EMAIL), 429, "rate_limited");
    }
}
