package com.bmp.inventory.api.events;

import com.bmp.shared.id.VariantId;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One stock movement in an event, in base units.
 *
 * @param qtyBase in > 0, out < 0; 0 for a cost correction
 * @param costAmount signed like {@code qtyBase} (INV-02)
 */
public record StockMovementLine(
        UUID movementId,
        @Nullable UUID sourceLineId,
        VariantId variantId,
        String stockState,
        @Nullable UUID batchId,
        BigDecimal qtyBase,
        BigDecimal unitCost,
        BigDecimal costAmount) {}
