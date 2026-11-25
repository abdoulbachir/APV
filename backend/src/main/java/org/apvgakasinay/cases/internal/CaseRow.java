package org.apvgakasinay.cases.internal;

import java.time.Instant;
import java.time.LocalDate;

/** One public case as read from the database, before it is turned into a public response. */
public record CaseRow(
        long id,
        String code,
        String kind,
        String status,
        boolean acceptsDonations,
        String categoryId,
        Integer featuredRank,
        String publicName,
        String titleEn,
        String titleFr,
        String storyEn,
        String storyFr,
        Long goalCents,
        LocalDate deadlineDate,
        String deadlineTimezone,
        Instant deadlineEndsAt,
        Instant publishedAt,
        String coverToken,
        String coverFormat,
        String coverAltEn,
        String coverAltFr,
        long allocated,
        long delivered) {
}
