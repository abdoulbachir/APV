package org.apvgakasinay.donations.internal;

import java.time.LocalDate;
import java.time.ZoneId;
import org.apvgakasinay.site.SiteService;
import org.springframework.stereotype.Component;

/** Today in the organization timezone: the day against which the dates a person types are judged. */
@Component
class OrganizationCalendar {

    private final SiteService site;
    private final DonationClock clock;

    OrganizationCalendar(SiteService site, DonationClock clock) {
        this.site = site;
        this.clock = clock;
    }

    LocalDate today() {
        return LocalDate.ofInstant(clock.now(), ZoneId.of(site.site().timezone()));
    }
}
