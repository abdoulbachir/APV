package org.apvgakasinay.donations.internal;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * The identifiers of a gift and how they are written (contract/donation-lifecycle.md, 1.3).
 *
 * <p>References and tracking codes use 32 symbols: the digits and the upper-case letters
 * without the look-alikes I, L, O and U. One symbol is five bits, so the eight symbols of a
 * reference are exactly five bytes and the twelve secret symbols of a tracking code are sixty bits.
 */
final class Codes {

    static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    static final int REFERENCE_BYTES = 5;
    private static final char[] ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Codes() {
    }

    static byte[] random(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return value;
    }

    /** A new opaque identifier: the prefix and 22 random letters and digits, about 131 bits. */
    static String publicId(String prefix) {
        StringBuilder id = new StringBuilder(prefix);
        for (int i = 0; i < 22; i++) {
            id.append(ALPHANUMERIC[RANDOM.nextInt(ALPHANUMERIC.length)]);
        }
        return id.toString();
    }

    /** The first {@code symbols} symbols of the bytes, five bits each, most significant first. */
    static String encode(byte[] bytes, int symbols) {
        StringBuilder out = new StringBuilder(symbols);
        int buffer = 0;
        int bits = 0;
        int next = 0;
        while (out.length() < symbols) {
            if (bits < 5) {
                buffer = (buffer << 8) | (bytes[next++] & 0xff);
                bits += 8;
            }
            out.append(ALPHABET.charAt((buffer >> (bits - 5)) & 0x1f));
            bits -= 5;
        }
        return out.toString();
    }

    /** The bytes a run of symbols stands for; the inverse of {@link #encode} for whole bytes. Null when a symbol is not in the alphabet. */
    static byte[] decode(String symbols) {
        byte[] out = new byte[symbols.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int next = 0;
        for (int i = 0; i < symbols.length(); i++) {
            int value = ALPHABET.indexOf(symbols.charAt(i));
            if (value < 0) {
                return null;
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8 && next < out.length) {
                out[next++] = (byte) ((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out;
    }

    /** A new reference: 40 random bits written XXXX-XXXX. */
    static String newReference() {
        return dashed(encode(random(REFERENCE_BYTES), 8));
    }

    /** Groups of four separated by dashes. */
    static String dashed(String symbols) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < symbols.length(); i += 4) {
            out.append(i == 0 ? "" : "-").append(symbols, i, Math.min(i + 4, symbols.length()));
        }
        return out.toString();
    }

    /**
     * What someone typed as a reference or a tracking code, without dashes and spaces and in
     * upper case, when it then has exactly {@code symbols} symbols of the alphabet. Null otherwise.
     */
    static String normalize(String typed, int symbols) {
        if (typed == null || typed.length() > 64) {
            return null;
        }
        String bare = typed.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
        if (bare.length() != symbols) {
            return null;
        }
        for (int i = 0; i < bare.length(); i++) {
            if (ALPHABET.indexOf(bare.charAt(i)) < 0) {
                return null;
            }
        }
        return bare;
    }
}
