package org.apvgakasinay.donations.internal;

import java.time.Instant;
import java.time.InstantSource;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * "Now" for everything the donations module does: the instant of a report and of a decision,
 * the date of a ledger entry, upload expiry, rate-limit windows and retention. It is the real
 * time in every profile, like identity's clock and for the same reason: the clock of the public
 * read responses is pinned by the development fixture loader, and a pinned clock would never
 * end a window or an upload token. Only the automated tests move it, by supplying a time source.
 */
@Component
public class DonationClock {

    private final InstantSource source;

    public DonationClock(ObjectProvider<InstantSource> source) {
        this.source = source.getIfAvailable(InstantSource::system);
    }

    public Instant now() {
        return source.instant().truncatedTo(ChronoUnit.SECONDS);
    }
}
