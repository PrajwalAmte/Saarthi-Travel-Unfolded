package io.destinationintel.common;

import java.util.List;
import java.util.function.Function;

/** Consistent paginated response envelope. Pages are zero-based. */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

    public static final int MAX_PAGE_SIZE = 50;

    public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalItems) {
        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) totalItems / size);
        return new PageResponse<>(items, page, size, totalItems, totalPages);
    }

    /** Slices an already-ordered in-memory list. */
    public static <T> PageResponse<T> slice(List<T> all, int page, int size) {
        int from = Math.min(all.size(), page * size);
        int to = Math.min(all.size(), from + size);
        return of(all.subList(from, to), page, size, all.size());
    }

    public <R> PageResponse<R> map(Function<T, R> fn) {
        return new PageResponse<>(items.stream().map(fn).toList(), page, size, totalItems, totalPages);
    }

    public static int clampSize(Integer size, int defaultSize) {
        if (size == null || size <= 0) {
            return defaultSize;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    public static int clampPage(Integer page) {
        return page == null || page < 0 ? 0 : Math.min(page, 10_000);
    }
}
