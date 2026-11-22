package com.bmp.catalog.api.events;

import com.bmp.shared.id.PackagingUnitId;
import com.bmp.shared.id.VariantId;
import java.math.BigDecimal;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One line of a published transformation definition version.
 *
 * @param direction IN (consumed) or OUT (produced)
 * @param variantId the variant
 * @param stockState the stock state taken from or added to
 * @param qtyBase base units per run
 * @param packagingUnitId the packaging unit that sizes the line, if any
 * @param costSharePct OUT lines: share of the input cost
 */
public record TransformationLineSnapshot(
        String direction,
        VariantId variantId,
        String stockState,
        BigDecimal qtyBase,
        @Nullable PackagingUnitId packagingUnitId,
        @Nullable BigDecimal costSharePct) {

    public TransformationLineSnapshot {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(variantId, "variantId");
        Objects.requireNonNull(stockState, "stockState");
        Objects.requireNonNull(qtyBase, "qtyBase");
    }
}
