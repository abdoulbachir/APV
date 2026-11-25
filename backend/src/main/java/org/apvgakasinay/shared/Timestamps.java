package org.apvgakasinay.shared;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** The one timestamp format of the API: RFC 3339, UTC, whole seconds, "Z" suffix. */
public final class Timestamps {

    private Timestamps() {
    }

    public static String format(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.SECONDS).toString();
    }
}
