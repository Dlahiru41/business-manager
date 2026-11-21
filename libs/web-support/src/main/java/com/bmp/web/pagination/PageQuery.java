package com.bmp.web.pagination;

import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.PlatformErrorCode;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A validated page request: default limit 50, maximum 200 (400 above), plus the opaque cursor of the previous page.
 *
 * @param limit page size, 1–200
 * @param cursor cursor from the previous page's {@code nextCursor}, or {@code null} for the first page
 */
public record PageQuery(int limit, @Nullable String cursor) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    public PageQuery {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new BusinessRuleViolation(
                    PlatformErrorCode.INVALID_PARAMETER,
                    "limit must be between 1 and " + MAX_LIMIT,
                    Map.of("parameter", "limit", "max", MAX_LIMIT));
        }
        if (cursor != null && cursor.isBlank()) {
            cursor = null;
        }
    }

    public static PageQuery of(@Nullable Integer limit, @Nullable String cursor) {
        return new PageQuery(limit != null ? limit : DEFAULT_LIMIT, cursor);
    }

    /** Rows to fetch: one more than the limit, to know whether another page exists. */
    public int fetchSize() {
        return limit + 1;
    }
}
