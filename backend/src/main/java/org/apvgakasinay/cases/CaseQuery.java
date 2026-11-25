package org.apvgakasinay.cases;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.apvgakasinay.shared.ApiException;
import org.apvgakasinay.shared.PageRequest;
import org.apvgakasinay.shared.SearchText;

/** Validated query parameters of GET /cases. */
public record CaseQuery(List<String> statuses, String normalizedSearch, String category, Sort sort, PageRequest page) {

    public enum Sort { NEWEST, DEADLINE, CLOSEST, LEAST, FEATURED }

    private static final Pattern CATEGORY_ID = Pattern.compile("^[a-z][a-z0-9-]{1,31}$");

    public static CaseQuery from(Map<String, String> query) {
        String status = query.getOrDefault("status", "active");
        List<String> statuses = switch (status) {
            case "active" -> List.of("active", "funded");
            case "completed" -> List.of("delivered", "closed");
            default -> throw ApiException.invalid("status", "invalid_value", "status must be active or completed");
        };

        Sort sort = switch (query.getOrDefault("sort", "newest")) {
            case "newest" -> Sort.NEWEST;
            case "deadline" -> Sort.DEADLINE;
            case "closest" -> Sort.CLOSEST;
            case "least" -> Sort.LEAST;
            case "featured" -> Sort.FEATURED;
            default -> throw ApiException.invalid("sort", "invalid_value", "sort must be newest, deadline, closest, least or featured");
        };

        String category = query.get("category");
        if (category != null && !CATEGORY_ID.matcher(category).matches()) {
            throw ApiException.invalid("category", "invalid_value", "category is not a category identifier");
        }

        return new CaseQuery(statuses, search(query.get("q")), category, sort, PageRequest.from(query));
    }

    /** Length limits are counted in Unicode code points and checked before normalization. */
    private static String search(String raw) {
        if (raw == null) {
            return "";
        }
        if (SearchText.codePoints(raw) > SearchText.MAX_RAW_CODE_POINTS) {
            throw ApiException.invalid("q", "too_long", "q must be at most " + SearchText.MAX_RAW_CODE_POINTS + " characters");
        }
        if (SearchText.codePoints(SearchText.trim(raw)) > SearchText.MAX_TRIMMED_CODE_POINTS) {
            throw ApiException.invalid("q", "too_long", "q must be at most " + SearchText.MAX_TRIMMED_CODE_POINTS + " characters after trimming");
        }
        return SearchText.normalize(raw);
    }
}
