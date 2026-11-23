package com.bmp.inventory.domain.policy;

import com.bmp.inventory.domain.model.BalanceState;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * How stock is costed (INV-03, INV-04). Money is rounded per the tenant policy, unit costs kept at scale 6. Both
 * policies keep a balance's value equal to the sum of its movements' costs, corrections included.
 */
public sealed interface CostingPolicy permits MovingAverageCosting, FifoCosting {

    /**
     * Stock comes in.
     *
     * @param qty positive, base units
     * @param cost what it cost in total, not negative
     * @param layerId the id a FIFO layer would get
     */
    Costed receive(BalanceState state, BigDecimal qty, BigDecimal cost, UUID layerId, Instant at);

    /**
     * Stock goes out at current cost.
     *
     * @param qty positive, base units
     */
    Costed issue(BalanceState state, BigDecimal qty);

    /** What {@code qty} base units are worth now: for gains costed "at current cost" (counts). */
    BigDecimal valueAtCurrentCost(BalanceState state, BigDecimal qty);
}
