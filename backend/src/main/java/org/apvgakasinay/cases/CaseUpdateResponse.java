package org.apvgakasinay.cases;

/** Public view of an update posted on a case. */
public record CaseUpdateResponse(
        String id,
        String caseCode,
        String text,
        String textLanguage,
        String photoUrl,
        String photoAlt,
        String photoAltLanguage,
        String date,
        String author,
        String authorAvatarUrl) {
}
