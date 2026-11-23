package com.bmp.inventory.domain.model;

import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An input or output of a transformation run.
 *
 * @param expectedQtyBase outputs: what the definition expected
 * @param qtyBase what was consumed or obtained, positive
 * @param costSharePct outputs: their share of the input cost
 * @param costAmount positive
 */
public record RunLine(
        UUID movementId,
        UUID variantId,
        String stockState,
        @Nullable BigDecimal expectedQtyBase,
        BigDecimal qtyBase,
        @Nullable BigDecimal costSharePct,
        BigDecimal costAmount,
        BigDecimal unitCost) {}
