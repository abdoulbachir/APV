package org.apvgakasinay.cases;

/** Public view of a case or of the general fund. Exactly the {@code Case} schema. */
public record CaseResponse(
        String code,
        String kind,
        String status,
        boolean acceptingDonations,
        String category,
        Integer featuredRank,
        String title,
        String titleLanguage,
        String publicName,
        String story,
        String storyLanguage,
        String photoUrl,
        String photoThumbnailUrl,
        String photoAlt,
        String photoAltLanguage,
        Long goal,
        long received,
        long delivered,
        String deadline,
        String deadlineTimezone,
        String deadlineEndsAt,
        String createdAt) {
}
