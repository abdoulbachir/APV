package org.apvgakasinay.site;

import java.util.List;
import org.apvgakasinay.cases.CategoryResponse;

/** Site settings. Exactly the {@code Site} schema. */
public record SiteResponse(
        String timezone,
        String currency,
        List<String> locales,
        String defaultLocale,
        List<CategoryResponse> categories,
        String fundCode,
        Pagination pagination) {

    public record Pagination(int defaultPageSize, int maxPageSize) {
    }
}
