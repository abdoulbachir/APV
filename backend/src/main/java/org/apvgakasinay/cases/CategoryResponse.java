package org.apvgakasinay.cases;

/** Public view of a category. Both labels are always present. */
public record CategoryResponse(String id, Labels labels, int position, boolean active) {

    public record Labels(String en, String fr) {
    }
}
