package com.bmp.pricing.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A basket to price.
 *
 * @param channel {@code RETAIL} or {@code WHOLESALE}
 * @param at when the prices apply
 */
public record BasketQuote(
        String channel, @Nullable UUID customerId, @Nullable UUID customerGroupId, Instant at, List<Line> lines) {

    public BasketQuote {
        lines = List.copyOf(lines);
    }

    /** A line: a quantity of a packaging unit. */
    public record Line(String lineId, UUID variantId, UUID packagingUnitId, BigDecimal qty) {}
}
