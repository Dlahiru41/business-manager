package com.bmp.pricing.domain.service;

import com.bmp.pricing.domain.model.SalesChannel;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A basket to price.
 *
 * @param channel the bill's channel
 * @param customerId the customer, if any
 * @param customerGroupId the customer's group, if any
 * @param at the moment prices and rules must be valid at
 * @param businessDate the tenant-local date of {@code at}, for tax rates
 * @param lines the basket lines
 */
public record ResolutionRequest(
        SalesChannel channel,
        @Nullable UUID customerId,
        @Nullable UUID customerGroupId,
        Instant at,
        LocalDate businessDate,
        List<Line> lines) {

    public ResolutionRequest {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(businessDate, "businessDate");
        lines = List.copyOf(lines);
    }

    /**
     * One line.
     *
     * @param lineId the caller's reference, echoed back
     * @param variantId the variant
     * @param packagingUnitId the unit sold
     * @param qty how many of the unit, positive
     */
    public record Line(@Nullable String lineId, UUID variantId, UUID packagingUnitId, BigDecimal qty) {}
}
