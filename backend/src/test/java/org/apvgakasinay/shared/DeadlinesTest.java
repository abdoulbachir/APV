package org.apvgakasinay.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class DeadlinesTest {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

    @Test
    void winterDeadlineEndsAtSixUtc() {
        assertThat(Deadlines.cutoff(LocalDate.parse("2027-01-18"), CHICAGO)).isEqualTo(Instant.parse("2027-01-19T06:00:00Z"));
    }

    @Test
    void deadlineOnTheDayClocksMoveForwardEndsAtFiveUtc() {
        // 2027-03-14 has 23 hours in Chicago.
        assertThat(Deadlines.cutoff(LocalDate.parse("2027-03-14"), CHICAGO)).isEqualTo(Instant.parse("2027-03-15T05:00:00Z"));
        assertThat(Deadlines.cutoff(LocalDate.parse("2027-03-13"), CHICAGO)).isEqualTo(Instant.parse("2027-03-14T06:00:00Z"));
    }

    @Test
    void deadlineOnTheDayClocksMoveBackEndsAtSixUtc() {
        // 2026-11-01 has 25 hours in Chicago.
        assertThat(Deadlines.cutoff(LocalDate.parse("2026-10-31"), CHICAGO)).isEqualTo(Instant.parse("2026-11-01T05:00:00Z"));
        assertThat(Deadlines.cutoff(LocalDate.parse("2026-11-01"), CHICAGO)).isEqualTo(Instant.parse("2026-11-02T06:00:00Z"));
    }

    @Test
    void theSameDateEndsAtDifferentInstantsInDifferentZones() {
        LocalDate day = LocalDate.parse("2027-02-01");
        assertThat(Deadlines.cutoff(day, ZoneId.of("Pacific/Kiritimati"))).isEqualTo(Instant.parse("2027-02-01T10:00:00Z"));
        assertThat(Deadlines.cutoff(day, ZoneId.of("Europe/Paris"))).isEqualTo(Instant.parse("2027-02-01T23:00:00Z"));
        assertThat(Deadlines.cutoff(day, CHICAGO)).isEqualTo(Instant.parse("2027-02-02T06:00:00Z"));
    }

    @Test
    void endOfMonthAndYearRollOver() {
        assertThat(Deadlines.cutoff(LocalDate.parse("2026-12-31"), CHICAGO)).isEqualTo(Instant.parse("2027-01-01T06:00:00Z"));
        assertThat(Deadlines.cutoff(LocalDate.parse("2028-02-29"), CHICAGO)).isEqualTo(Instant.parse("2028-03-01T06:00:00Z"));
    }
}
