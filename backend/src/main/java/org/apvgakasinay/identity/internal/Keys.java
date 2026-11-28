package org.apvgakasinay.identity.internal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Every use of a secret key and of randomness in the identity module, on the JDK alone.
 *
 * Two keys come from the environment and never from the database: one encrypts authenticator
 * secrets, the other is the root of the keyed hashes. A copy of the database alone therefore
 * reveals no authenticator secret, and its recovery-code hashes and rate-limit keys cannot be
 * tested offline.
 */
@Component
public class Keys {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec encryptionKey;
    private final byte[] hashKey;
    private final byte[] recoveryCodeKey;
    private final byte[] throttleKey;

    public Keys(IdentityProperties properties) {
        this.encryptionKey = new SecretKeySpec(properties.encryptionKey(), "AES");
        this.hashKey = properties.hashKey();
        // Two independent keys derived from the one hashing key, so a hash of one kind is useless as the other.
        this.recoveryCodeKey = hmac(properties.hashKey(), "apv/recovery-code/v1".getBytes(StandardCharsets.UTF_8));
        this.throttleKey = hmac(properties.hashKey(), "apv/throttle/v1".getBytes(StandardCharsets.UTF_8));
    }

    static byte[] random(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return value;
    }

    /** A new account identifier: "acc_" and 22 random letters and digits, about 131 bits. */
    public static String accountPublicId() {
        StringBuilder id = new StringBuilder("acc_");
        for (int i = 0; i < 22; i++) {
            id.append(ALPHANUMERIC[RANDOM.nextInt(ALPHANUMERIC.length)]);
        }
        return id.toString();
    }

    /** A new one-time credential: 256 random bits as 43 URL-safe characters. */
    public static String oneTimeCredential() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random(32));
    }

    /** A new recovery code: 80 random bits as XXXX-XXXX-XXXX-XXXX in Base32. */
    public static String recoveryCode() {
        String raw = base32(random(10));
        return raw.substring(0, 4) + "-" + raw.substring(4, 8) + "-" + raw.substring(8, 12) + "-" + raw.substring(12, 16);
    }

    /** A new authenticator secret: 160 random bits. */
    public static byte[] totpSecret() {
        return random(20);
    }

    public static String base32(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32[(buffer >> (bits - 5)) & 0x1f]);
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32[(buffer << (5 - bits)) & 0x1f]);
        }
        return out.toString();
    }

    /** SHA-256 of a one-time credential. It has 256 bits of entropy, so no key or salt is needed. */
    public static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Keyed hash of a recovery code, in its canonical form: upper case, no dashes. */
    public byte[] recoveryCodeHash(String code) {
        return hmac(recoveryCodeKey, code.replace("-", "").getBytes(StandardCharsets.UTF_8));
    }

    /** Keyed hash of a rate-limit key, so counters hold neither emails nor addresses. */
    public byte[] throttleKeyHash(String key) {
        return hmac(throttleKey, key.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A key for another purpose, derived from the hashing key the way this class derives its
     * own: one key per label, so a value keyed for one purpose is useless for any other. The
     * hashing key itself never leaves this class.
     */
    public byte[] derive(String label) {
        if (!label.startsWith("apv/") || label.startsWith("apv/recovery-code/") || label.startsWith("apv/throttle/")) {
            throw new IllegalArgumentException("not a label another module may derive a key for");
        }
        return hmac(hashKey, label.getBytes(StandardCharsets.UTF_8));
    }

    /** AES-256-GCM. The result is the random nonce followed by the ciphertext and its tag. */
    public byte[] encrypt(byte[] plain) {
        try {
            byte[] nonce = random(NONCE_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] sealed = cipher.doFinal(plain);
            return ByteBuffer.allocate(nonce.length + sealed.length).put(nonce).put(sealed).array();
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("encryption failed", failure);
        }
    }

    public byte[] decrypt(byte[] stored) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, stored, 0, NONCE_BYTES));
            return cipher.doFinal(Arrays.copyOfRange(stored, NONCE_BYTES, stored.length));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("a stored authenticator secret cannot be decrypted with the configured key", failure);
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
