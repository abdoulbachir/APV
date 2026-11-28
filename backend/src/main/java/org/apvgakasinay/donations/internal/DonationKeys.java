package org.apvgakasinay.donations.internal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apvgakasinay.identity.DerivedKeys;
import org.springframework.stereotype.Component;

/**
 * Every keyed value of the donations module (contract/donation-lifecycle.md, 3.6).
 *
 * <p>Four keys are derived from the hashing key that lives in the environment, one per purpose.
 * The tracking code and the evidence upload token are computed from a random value stored with
 * the gift and one of those keys, so the same answer can be given twice without a secret ever
 * being stored, and a copy of the database alone yields neither. Rate-limit and idempotency keys
 * are stored only as keyed hashes for the same reason.
 */
@Component
class DonationKeys {

    private static final int TOKEN_BYTES = 32;

    private final byte[] trackingKey;
    private final byte[] evidenceKey;
    private final byte[] throttleKey;
    private final byte[] idempotencyKey;

    DonationKeys(DerivedKeys keys) {
        this.trackingKey = keys.derive("apv/donation/tracking-code/v1");
        this.evidenceKey = keys.derive("apv/donation/evidence-token/v1");
        this.throttleKey = keys.derive("apv/donation/throttle/v1");
        this.idempotencyKey = keys.derive("apv/donation/idempotency/v1");
    }

    /** The twelve secret symbols of a gift's tracking code: sixty bits of a keyed hash of its secret. */
    String trackingTail(byte[] secret) {
        return Codes.encode(hmac(trackingKey, secret), 12);
    }

    /** The tracking code as the receipt shows it: the reference, then the secret part, in five groups of four. */
    String trackingCode(String reference, byte[] secret) {
        return reference + "-" + Codes.dashed(trackingTail(secret));
    }

    /**
     * The upload token of a gift: the five bytes of its reference, which is how the gift is
     * found again, then 27 bytes of a keyed hash of its secret, as 43 URL-safe characters.
     */
    String evidenceToken(String reference, byte[] secret) {
        byte[] locator = Codes.decode(reference.replace("-", ""));
        byte[] proof = hmac(evidenceKey, secret);
        byte[] token = ByteBuffer.allocate(TOKEN_BYTES).put(locator).put(proof, 0, TOKEN_BYTES - locator.length).array();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    /** The reference a well-formed upload token names, or null when it is not a token at all. */
    static String referenceOf(String token) {
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(token);
        } catch (IllegalArgumentException notBase64) {
            return null;
        }
        if (bytes.length != TOKEN_BYTES) {
            return null;
        }
        return Codes.dashed(Codes.encode(Arrays.copyOf(bytes, Codes.REFERENCE_BYTES), 8));
    }

    byte[] throttleHash(String key) {
        return hmac(throttleKey, key.getBytes(StandardCharsets.UTF_8));
    }

    /** Keyed hash of an Idempotency-Key header, or of the request it was sent with. */
    byte[] idempotencyHash(byte[] value) {
        return hmac(idempotencyKey, value);
    }

    /** Compares two secrets in time that does not depend on where they differ. */
    static boolean same(String expected, String given) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] hmac(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
