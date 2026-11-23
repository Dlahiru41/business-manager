package com.bmp.inventory.domain.model;

import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A reorder level of a variant at a location (INV-13), in base units.
 *
 * @param lowFlag set when available stock crossed the level, cleared when it rose above it again
 */
public record ReorderPolicy(
        UUID variantId,
        UUID locationId,
        BigDecimal reorderLevel,
        BigDecimal reorderQty,
        @Nullable UUID preferredSupplierId,
        boolean lowFlag,
        long version) {}
