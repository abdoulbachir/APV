package org.apvgakasinay.cases;

/** An additional public picture of a case. */
public record CasePhotoResponse(String id, String url, String thumbnailUrl, String alt, String altLanguage, int position) {
}
