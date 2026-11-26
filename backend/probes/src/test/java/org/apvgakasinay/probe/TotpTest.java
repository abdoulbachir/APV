package org.apvgakasinay.probe;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The verifier against the published test vectors of RFC 6238, appendix B (SHA-1), cut to six digits. */
class TotpTest {

    private static final byte[] SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void matchesTheRfc6238Sha1Vectors() {
        long[] times = {59L, 1111111109L, 1111111111L, 1234567890L, 2000000000L, 20000000000L};
        String[] eightDigits = {"94287082", "07081804", "14050471", "89005924", "69279037", "65353130"};
        for (int i = 0; i < times.length; i++) {
            assertThat(Totp.code(SECRET, Totp.step(Instant.ofEpochSecond(times[i]))))
                    .as("time %d", times[i]).isEqualTo(eightDigits[i].substring(2));
        }
    }

    @Test
    void acceptsOneStepEitherSideAndNoFurther() {
        Instant now = Instant.ofEpochSecond(1111111111L);
        long step = Totp.step(now);
        assertThat(Totp.matchingStep(SECRET, Totp.code(SECRET, step), now)).isEqualTo(step);
        assertThat(Totp.matchingStep(SECRET, Totp.code(SECRET, step - 1), now)).isEqualTo(step - 1);
        assertThat(Totp.matchingStep(SECRET, Totp.code(SECRET, step + 1), now)).isEqualTo(step + 1);
        assertThat(Totp.matchingStep(SECRET, Totp.code(SECRET, step - 2), now)).isEqualTo(-1);
        assertThat(Totp.matchingStep(SECRET, Totp.code(SECRET, step + 2), now)).isEqualTo(-1);
    }
}
