package org.apvgakasinay.shared;

import java.util.List;
import java.util.Locale;

/** The two supported languages, and the Accept-Language rules of the contract. */
public enum Language {
    EN("en"),
    FR("fr");

    private final String tag;

    Language(String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }

    public Language other() {
        return this == EN ? FR : EN;
    }

    /**
     * Picks the first supported language of the header, honoring quality values and ignoring
     * region subtags. A missing, malformed or unsupported header means English.
     */
    public static Language negotiate(String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            return EN;
        }
        List<Locale.LanguageRange> ranges;
        try {
            ranges = Locale.LanguageRange.parse(acceptLanguage);
        } catch (IllegalArgumentException malformed) {
            return EN;
        }
        for (Locale.LanguageRange range : ranges) {
            if (range.getWeight() <= 0) {
                continue;
            }
            String primary = range.getRange().split("-", 2)[0];
            if (primary.equals("en")) {
                return EN;
            }
            if (primary.equals("fr")) {
                return FR;
            }
        }
        return EN;
    }
}
