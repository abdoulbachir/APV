package org.apvgakasinay.shared;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Deadline arithmetic of contract/financial-terms.md, section 10. */
public final class Deadlines {

    private Deadlines() {
    }

    /**
     * The cutoff of a deadline: the first instant of the day after {@code lastDay} in
     * {@code zone}. Computed with the zone's own rules, so daylight saving changes are handled.
     */
    public static Instant cutoff(LocalDate lastDay, ZoneId zone) {
        return lastDay.plusDays(1).atStartOfDay(zone).toInstant();
    }
}
