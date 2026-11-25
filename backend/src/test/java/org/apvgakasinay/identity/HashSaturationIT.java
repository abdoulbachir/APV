package org.apvgakasinay.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.apvgakasinay.identity.internal.PasswordHasher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Scenario 8.11 over real HTTP, with the accepted limits (four hashes at once, two seconds of
 * waiting). Only the cost of one hash is raised, so that more sign-ins than can be served in
 * two seconds are easy to produce.
 */
@TestPropertySource(properties = {"apv.identity.argon2.memory-kib=131072", "apv.identity.argon2.iterations=12"})
class HashSaturationIT extends IdentityIntegration {

    @Autowired
    PasswordHasher hasher;

    @Test
    void a8_11_moreSignInsThanHashSlotsNeverRunMoreThanFourHashesAndTheRestAreToldToComeBack() {
        Runtime runtime = Runtime.getRuntime();
        long before = runtime.totalMemory() - runtime.freeMemory();
        // Forty sign-ins at the same moment, each from its own client address and for its own address,
        // so no rate limit is what stops them.
        List<Callable<Browser.Answer>> calls = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            Browser browser = browser();
            browser.csrf();
            String email = "person" + i + "@example.org";
            calls.add(() -> browser.post("/api/auth/login", json("email", email, "password", PASSWORD)));
        }
        long started = System.nanoTime();
        List<Browser.Answer> answers = together(calls);
        long millis = (System.nanoTime() - started) / 1_000_000;

        long checked = answers.stream().filter(answer -> answer.status() == 401).count();
        long turnedAway = answers.stream().filter(answer -> answer.status() == 503).count();
        assertThat(checked + turnedAway).as("every answer is one or the other").isEqualTo(40);
        assertThat(checked).as("the slots did serve").isGreaterThanOrEqualTo(4);
        assertThat(turnedAway).as("requests that could not start within two seconds").isGreaterThanOrEqualTo(1);
        for (Browser.Answer answer : answers) {
            if (answer.status() == 503) {
                assertError(answer, 503, "unavailable");
                assertThat(answer.header("Retry-After")).isEqualTo("5");
            } else {
                assertError(answer, 401, "invalid_credentials");
            }
        }
        // Never more than four at once, in this process, since it started.
        assertThat(hasher.peakConcurrency()).isBetween(1, 4);
        // A password that was turned away was not looked at: no failure was recorded for it.
        assertThat(count("select count(*) from audit_event where event_type = 'session.sign_in'")).isEqualTo(checked);

        long after = runtime.totalMemory() - runtime.freeMemory();
        Evidence.write("hash-saturation", String.format(
                "40 simultaneous sign-ins, hash cost m=131072 KiB t=12, limits 4 at once / 2000 ms wait.%n"
                        + "Password checked: %d. Turned away with 503 and Retry-After 5: %d. Peak simultaneous hashes: %d. Elapsed: %d ms.%n"
                        + "Heap in use before: %d MiB, after: %d MiB (four hashes of 128 MiB is the bound on what hashing adds).%n",
                checked, turnedAway, hasher.peakConcurrency(), millis, before >> 20, after >> 20));
        // The server answers normally afterwards.
        assertThat(browser().get("/api/auth/options").status()).isEqualTo(200);
    }
}
