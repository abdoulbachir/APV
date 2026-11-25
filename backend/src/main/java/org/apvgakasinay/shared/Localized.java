package org.apvgakasinay.shared;

/**
 * A text returned in one language, with the language actually used. {@link #NONE} stands for
 * "no text at all" (for example the description of a picture that does not exist).
 */
public record Localized(String text, String language) {

    public static final Localized NONE = new Localized(null, null);

    /** Returns the text in the wanted language, or falls back to the other one. */
    public static Localized pick(Language wanted, String english, String french) {
        String preferred = wanted == Language.EN ? english : french;
        if (preferred != null) {
            return new Localized(preferred, wanted.tag());
        }
        String fallback = wanted == Language.EN ? french : english;
        return fallback == null ? NONE : new Localized(fallback, wanted.other().tag());
    }
}
