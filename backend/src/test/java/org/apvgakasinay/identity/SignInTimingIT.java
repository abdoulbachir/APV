package org.apvgakasinay.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Scenario 1.7: a sign-in for an address with no account is not answered faster than a wrong
 * password for an existing one. Measured over many interleaved requests, compared by median.
 */
class SignInTimingIT extends IdentityIntegration {

    private static final int ACCOUNTS = 12;
    private static final int SAMPLES = 60;

    private double millis(String email) {
        Browser browser = browser();
        browser.csrf();
        long started = System.nanoTime();
        Browser.Answer answer = browser.post("/api/auth/login", json("email", email, "password", OTHER_PASSWORD));
        long elapsed = System.nanoTime() - started;
        assertError(answer, 401, "invalid_credentials");
        return elapsed / 1_000_000.0;
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return (sorted.get(sorted.size() / 2) + sorted.get((sorted.size() - 1) / 2)) / 2;
    }

    @Test
    void a1_7_anUnknownAddressIsNotAnsweredFasterThanAWrongPassword() {
        for (int i = 0; i < ACCOUNTS; i++) {
            register(browser(), "existing" + i + "@example.org", PASSWORD);
        }
        // Warm-up, not measured.
        for (int i = 0; i < 6; i++) {
            millis("existing" + (i % ACCOUNTS) + "@example.org");
            millis("warmup" + i + "@example.org");
        }
        List<Double> existing = new ArrayList<>();
        List<Double> unknown = new ArrayList<>();
        // Interleaved, so drift of the machine affects both alike. Five per existing account stays under its own limit.
        for (int i = 0; i < SAMPLES; i++) {
            if (i % 2 == 0) {
                existing.add(millis("existing" + (i % ACCOUNTS) + "@example.org"));
                unknown.add(millis("nobody" + i + "@example.org"));
            } else {
                unknown.add(millis("nobody" + i + "@example.org"));
                existing.add(millis("existing" + (i % ACCOUNTS) + "@example.org"));
            }
        }
        double existingMedian = median(existing);
        double unknownMedian = median(unknown);
        double ratio = unknownMedian / existingMedian;
        Evidence.write("sign-in-timing", String.format(
                "Wrong password, %d requests each, interleaved, after warm-up.%n"
                        + "Existing account: median %.1f ms. Unknown address: median %.1f ms. Ratio unknown/existing: %.3f.%n"
                        + "Accepted band for the ratio: 0.80 to 1.25 (both verify one Argon2id hash; the rest is noise of a shared CI machine).%n",
                SAMPLES, existingMedian, unknownMedian, ratio));
        // Not faster beyond noise, and not slower either: a difference in any direction would tell the two apart.
        assertThat(ratio).as("existing %.1f ms, unknown %.1f ms", existingMedian, unknownMedian).isBetween(0.80, 1.25);
    }
}
