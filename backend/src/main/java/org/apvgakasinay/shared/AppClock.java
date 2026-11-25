package org.apvgakasinay.shared;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * "Now" for the public read responses (deadlines, "as of"). It is the system clock, unless the
 * development-only fixture importer pins it to the clock written in a fixture file so those
 * responses are repeatable.
 *
 * Identity never uses this clock: sign-in, authenticator codes, sessions and rate limits run
 * on real time in every profile (identity's own IdentityClock).
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
