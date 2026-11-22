package com.bmp.web.pagination;

import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * A page of results in the standard envelope {@code {"items": [...], "page": {"limit", "nextCursor", "hasMore"}}}.
 *
 * @param items the items of this page
 * @param page paging information
 */
public record PageResponse<T>(List<T> items, Page page) {

    public PageResponse {
        items = List.copyOf(items);
    }

    /**
     * Builds a page from rows fetched with {@link PageQuery#fetchSize()}: the extra row only signals that another page
     * exists and is dropped.
     *
     * @param cursorOf the cursor values of an item (sort key values, then id)
     */
    public static <T> PageResponse<T> of(List<T> fetched, PageQuery query, Function<T, List<String>> cursorOf) {
        boolean hasMore = fetched.size() > query.limit();
        List<T> items = hasMore ? fetched.subList(0, query.limit()) : fetched;
        String next = hasMore ? CursorCodec.encode(cursorOf.apply(items.getLast())) : null;
        return new PageResponse<>(items, new Page(query.limit(), next, hasMore));
    }

    public <R> PageResponse<R> map(Function<T, R> mapper) {
        return new PageResponse<>(items.stream().map(mapper).toList(), page);
    }

    /**
     * Paging information.
     *
     * @param limit the page size used
     * @param nextCursor cursor for the next page, {@code null} on the last page
     * @param hasMore whether another page exists
     */
    public record Page(int limit, @Nullable String nextCursor, boolean hasMore) {}
}
