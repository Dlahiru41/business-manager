package com.bmp.catalog.domain.model;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A transformation line to be set on a definition, with its base quantity resolved (from {@code qty} packaging units
 * when it names one).
 *
 * @param direction IN (consumed) or OUT (produced)
 * @param variantId a variant of the same tenant
 * @param stockState one of the variant's states
 * @param qtyBase base units per run; positive
 * @param packagingUnitId the packaging unit the line counts in, if any
 * @param qty number of packaging units per run, with {@code packagingUnitId}
 * @param costSharePct OUT lines: share of the input cost; null on a single OUT line means 100
 */
public record LineDraft(
        Direction direction,
        UUID variantId,
        String stockState,
        BigDecimal qtyBase,
        @Nullable UUID packagingUnitId,
        @Nullable BigDecimal qty,
        @Nullable BigDecimal costSharePct) {

    public LineDraft {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(variantId, "variantId");
        Objects.requireNonNull(stockState, "stockState");
        Objects.requireNonNull(qtyBase, "qtyBase");
    }

    LineDraft withCostShare(BigDecimal share) {
        return new LineDraft(direction, variantId, stockState, qtyBase, packagingUnitId, qty, share);
    }
}
