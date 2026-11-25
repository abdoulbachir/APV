package org.apvgakasinay.support;

import java.time.Instant;
import java.time.InstantSource;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The time identity runs on in the tests that need to move it (expiry, authenticator steps,
 * rate-limit windows). The application has no such thing: its identity clock is the real time
 * unless a time source like this one is supplied as a bean, and only tests that import this
 * class supply one. Until a test pins it, it is the real time too.
 */
public class TestTime implements InstantSource {

    private final AtomicReference<Instant> pinned = new AtomicReference<>();

    @Override
    public Instant instant() {
        Instant fixed = pinned.get();
        return fixed != null ? fixed : Instant.now();
    }

    public Instant now() {
        return instant().truncatedTo(ChronoUnit.SECONDS);
    }

    public void pin(Instant instant) {
        pinned.set(instant);
    }
}
