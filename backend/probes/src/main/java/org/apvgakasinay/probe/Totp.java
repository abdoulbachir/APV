package org.apvgakasinay.probe;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 6238 time-based one-time passwords on the JDK alone: HMAC-SHA-1, 30-second steps, six digits. */
public final class Totp {

    public static final int STEP_SECONDS = 30;

    private Totp() {
    }

    public static long step(Instant instant) {
        return Math.floorDiv(instant.getEpochSecond(), STEP_SECONDS);
    }

    public static String code(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return String.format("%06d", binary % 1_000_000);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Returns the time step a code is valid for, looking one step either side of now, or -1.
     * The comparison takes the same time whatever the code.
     */
    public static long matchingStep(byte[] secret, String code, Instant now) {
        long current = step(now);
        long matched = -1;
        for (long candidate = current - 1; candidate <= current + 1; candidate++) {
            if (java.security.MessageDigest.isEqual(code(secret, candidate).getBytes(), code.getBytes())) {
                matched = candidate;
            }
        }
        return matched;
    }
}
