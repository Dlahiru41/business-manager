package com.bmp.inventory.domain.model;

import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A line of a published transformation definition: base units consumed (IN) or produced (OUT) per run.
 *
 * @param costSharePct OUT lines: their share of the input cost
 */
public record DefinitionLine(
        String direction,
        UUID variantId,
        String stockState,
        BigDecimal qtyBase,
        @Nullable UUID packagingUnitId,
        @Nullable BigDecimal costSharePct) {

    public boolean consumes() {
        return direction.equals("IN");
    }
}
