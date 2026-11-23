package com.bmp.inventory.domain.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * What a balance holds: quantity on hand in base units, its value, the average (or last known) unit cost, and its
 * FIFO layers oldest first (empty under moving average).
 */
public record BalanceState(BigDecimal onHand, BigDecimal value, BigDecimal avgUnitCost, List<CostLayer> layers) {

    public BalanceState {
        layers = List.copyOf(layers);
    }

    public static BalanceState empty() {
        return new BalanceState(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of());
    }
}
