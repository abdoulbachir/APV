package org.apvgakasinay.shared;

import java.util.Map;

/** Validated {@code page} and {@code pageSize} query parameters. */
public record PageRequest(int page, int pageSize) {

    public static final int DEFAULT_PAGE_SIZE = 12;
    public static final int MAX_PAGE_SIZE = 48;
    public static final int MAX_PAGE = 100_000;

    public static PageRequest from(Map<String, String> query) {
        return new PageRequest(
                integer(query, "page", 1, 1, MAX_PAGE),
                integer(query, "pageSize", DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE));
    }

    public long offset() {
        return (long) (page - 1) * pageSize;
    }

    private static int integer(Map<String, String> query, String name, int fallback, int min, int max) {
        String raw = query.get(name);
        if (raw == null) {
            return fallback;
        }
        long value;
        try {
            value = Long.parseLong(raw);
        } catch (NumberFormatException notAnInteger) {
            throw ApiException.invalid(name, "invalid_type", name + " must be an integer");
        }
        if (value < min || value > max) {
            throw ApiException.invalid(name, "out_of_range", name + " must be between " + min + " and " + max);
        }
        return (int) value;
    }
}
