package com.bmp.inventory.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A FIFO cost layer (INV-04): what is left of one receipt and what it is worth. The value is kept with the quantity
 * so consuming a whole layer takes exactly its value, without rounding residue.
 */
public record CostLayer(
        UUID id, BigDecimal qtyRemaining, BigDecimal valueRemaining, BigDecimal unitCost, Instant receivedAt) {

    public boolean isEmpty() {
        return qtyRemaining.signum() == 0;
    }
}
