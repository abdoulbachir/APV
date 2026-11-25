package org.apvgakasinay.identity.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apvgakasinay.shared.ApiException;
import org.springframework.stereotype.Component;

/**
 * The rules for choosing a password (identity-and-security.md, section 3). Nothing here talks
 * to any other machine: the list of blocked passwords is a file shipped with the application.
 */
@Component
public class PasswordPolicy {

    static final int MIN_CODE_POINTS = 15;
    static final int MAX_CODE_POINTS = 128;
    private static final List<String> SITE_NAMES = List.of("apvgakasinay", "gakasinay", "apv");

    private final Set<String> blocked = new HashSet<>();

    public PasswordPolicy(IdentityProperties properties) {
        try (InputStream bundled = PasswordPolicy.class.getResourceAsStream("/identity/password-blocklist.txt")) {
            load(bundled);
            if (!properties.blocklistPath().isBlank()) {
                try (InputStream extra = Files.newInputStream(Path.of(properties.blocklistPath()))) {
                    load(extra);
                }
            }
        } catch (IOException unreadable) {
            throw new IllegalStateException("cannot read the password blocklist", unreadable);
        }
    }

    private void load(InputStream source) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    blocked.add(normalize(line));
                }
            }
        }
    }

    /** NFC, and nothing else: no trimming, no case folding, no compatibility mapping. */
    public static String normalize(String password) {
        return Normalizer.normalize(password, Normalizer.Form.NFC);
    }

    static int codePoints(String text) {
        return text.codePointCount(0, text.length());
    }

    /**
     * Checks a password being chosen and returns its NFC form, which is what gets hashed.
     *
     * @param field the request property to name in the error
     * @param context words that must not make up the password: the email's first part, the display name
     */
    public String accept(String field, String password, List<String> context) {
        if (codePoints(password) > MAX_CODE_POINTS) {
            throw ApiException.invalid(field, "too_long", field + " must be at most " + MAX_CODE_POINTS + " characters");
        }
        String normalized = normalize(password);
        int length = codePoints(normalized);
        if (length < MIN_CODE_POINTS) {
            throw ApiException.invalid(field, "too_short", field + " must be at least " + MIN_CODE_POINTS + " characters");
        }
        if (length > MAX_CODE_POINTS) {
            throw ApiException.invalid(field, "too_long", field + " must be at most " + MAX_CODE_POINTS + " characters");
        }
        if (blocked.contains(normalized) || builtFromContext(normalized, context)) {
            throw ApiException.invalid(field, "compromised", field + " is too easy to guess");
        }
        return normalized;
    }

    /**
     * True when, after lower-casing and dropping everything but letters and digits, the
     * password contains a context word and fewer than 8 other characters remain once every
     * occurrence of the context words is removed.
     */
    static boolean builtFromContext(String password, List<String> context) {
        String reduced = reduce(password);
        List<String> words = new ArrayList<>(SITE_NAMES);
        for (String word : context) {
            String candidate = reduce(word);
            if (candidate.codePointCount(0, candidate.length()) >= 4) {
                words.add(candidate);
            }
        }
        // Longest first, so "apvgakasinay" is removed whole before "apv".
        words.sort((a, b) -> b.length() - a.length());
        String remainder = reduced;
        boolean found = false;
        for (String word : words) {
            if (remainder.contains(word)) {
                found = true;
                remainder = remainder.replace(word, "");
            }
        }
        return found && remainder.codePointCount(0, remainder.length()) < 8;
    }

    private static String reduce(String text) {
        StringBuilder kept = new StringBuilder();
        text.toLowerCase(Locale.ROOT).codePoints().filter(Character::isLetterOrDigit).forEach(kept::appendCodePoint);
        return kept.toString();
    }
}
