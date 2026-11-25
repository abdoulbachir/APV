package org.apvgakasinay.identity.internal;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 6238 time-based one-time passwords on the JDK alone: HMAC-SHA-1, 30-second steps, six
 * digits, as authenticator applications implement them. Verified against the RFC's vectors.
 */
public final class Totp {

    public static final int STEP_SECONDS = 30;
    private static final String ISSUER = "APV-GAKASINAY";

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
     * Returns the time step this code is valid for, looking one step either side of now to
     * tolerate clock drift, or -1. All three candidates are always compared.
     */
    public static long matchingStep(byte[] secret, String code, Instant now) {
        long current = step(now);
        long matched = -1;
        byte[] candidateCode = code.getBytes(StandardCharsets.US_ASCII);
        for (long candidate = current - 1; candidate <= current + 1; candidate++) {
            if (MessageDigest.isEqual(code(secret, candidate).getBytes(StandardCharsets.US_ASCII), candidateCode)) {
                matched = candidate;
            }
        }
        return matched;
    }

    /** The standard address an authenticator application reads from a QR code. */
    public static String otpauthUri(String base32Secret, String email) {
        return "otpauth://totp/" + ISSUER + ":" + URLEncoder.encode(email, StandardCharsets.UTF_8)
                + "?secret=" + base32Secret + "&issuer=" + ISSUER + "&algorithm=SHA1&digits=6&period=" + STEP_SECONDS;
    }
}
