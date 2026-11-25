package org.apvgakasinay.media;

/**
 * Builds the only kind of picture address a public response may contain:
 * {@code /media/public/<token>/<variant>.<format>}, a path on the site's own origin.
 */
public final class MediaPath {

    public static final String DISPLAY = "display";
    public static final String THUMBNAIL = "thumb";

    private MediaPath() {
    }

    /** Returns null when there is no published picture (token is null). */
    public static String of(String token, String format, String variant) {
        return token == null ? null : "/media/public/" + token + "/" + variant + "." + format;
    }
}
