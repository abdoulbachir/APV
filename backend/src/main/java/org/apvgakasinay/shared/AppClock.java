package org.apvgakasinay.shared;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * The application's notion of "now". It is the system clock, unless the development-only
 * fixture importer pins it to the clock written in a fixture file so responses are repeatable.
 */
@Component
public class AppClock {

    private final AtomicReference<Instant> pinned = new AtomicReference<>();

    public Instant now() {
        Instant fixed = pinned.get();
        return (fixed != null ? fixed : Instant.now()).truncatedTo(ChronoUnit.SECONDS);
    }

    /** Used only by the fixture importer, which exists only in the dev and test profiles. */
    public void pin(Instant instant) {
        pinned.set(instant);
    }
}
