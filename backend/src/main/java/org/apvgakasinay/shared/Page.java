package org.apvgakasinay.shared;

import java.util.List;

/** The paginated envelope of the contract. */
public record Page<T>(List<T> items, int page, int pageSize, long totalItems, long totalPages) {

    public static <T> Page<T> of(List<T> items, PageRequest request, long totalItems) {
        long totalPages = Math.max(1, (totalItems + request.pageSize() - 1) / request.pageSize());
        return new Page<>(items, request.page(), request.pageSize(), totalItems, totalPages);
    }
}
