package org.apvgakasinay.identity.internal;

import java.time.Instant;
import java.time.InstantSource;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * "Now" for everything identity does: authenticator codes, session deadlines, rate-limit
 * windows, credential expiry, enrollment windows. It is the real time in every profile.
 *
 * It is deliberately not {@link org.apvgakasinay.shared.AppClock}. That clock is pinned by the
 * development fixture loader so public read responses are repeatable; a pinned clock would
 * refuse every code a real authenticator shows, never end a rate-limit window and report
 * deadlines in the past. Nothing in the application can pin this one. Only the automated tests
 * move it, by supplying their own time source as a bean; the application defines none.
 */
@Component
public class IdentityClock {

    private final InstantSource source;

    public IdentityClock(ObjectProvider<InstantSource> source) {
        this.source = source.getIfAvailable(InstantSource::system);
    }

    public Instant now() {
        return source.instant().truncatedTo(ChronoUnit.SECONDS);
    }
}
