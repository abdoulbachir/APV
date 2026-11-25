package org.apvgakasinay.shared;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The search normalization of GET /cases, identical to the frontend's:
 * NFD, remove U+0300..U+036F, lower-case without locale rules, trim.
 * "White space" is exactly the set ECMAScript's String.prototype.trim removes.
 */
public final class SearchText {

    public static final int MAX_RAW_CODE_POINTS = 200;
    public static final int MAX_TRIMMED_CODE_POINTS = 80;

    private static final String ECMASCRIPT_WHITE_SPACE =
            "\\u0009-\\u000D\\u0020\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF";
    private static final Pattern EDGES =
            Pattern.compile("^[" + ECMASCRIPT_WHITE_SPACE + "]+|[" + ECMASCRIPT_WHITE_SPACE + "]+$");
    private static final Pattern COMBINING_MARKS = Pattern.compile("[\\u0300-\\u036F]");

    private SearchText() {
    }

    public static String trim(String text) {
        return EDGES.matcher(text).replaceAll("");
    }

    public static int codePoints(String text) {
        return text.codePointCount(0, text.length());
    }

    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        return trim(COMBINING_MARKS.matcher(decomposed).replaceAll("").toLowerCase(Locale.ROOT));
    }
}
