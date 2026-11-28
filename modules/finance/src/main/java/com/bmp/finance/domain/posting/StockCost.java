package com.bmp.finance.domain.posting;

import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The cost of one stock movement, signed like its quantity: in &gt; 0, out &lt; 0 (INV-02).
 *
 * @param sourceLineId the bill or receipt line it moved for, if any
 */
public record StockCost(@Nullable UUID sourceLineId, UUID variantId, BigDecimal costAmount) {

    public StockCost(UUID variantId, BigDecimal costAmount) {
        this(null, variantId, costAmount);
    }
}
