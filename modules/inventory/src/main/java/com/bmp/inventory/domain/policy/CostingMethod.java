package com.bmp.inventory.domain.policy;

import com.bmp.shared.money.RoundingPolicy;

/** The tenant setting {@code inventory.costingMethod}. */
public enum CostingMethod {
    MOVING_AVERAGE,
    FIFO;

    public CostingPolicy policy(RoundingPolicy rounding) {
        return this == FIFO ? new FifoCosting(rounding) : new MovingAverageCosting(rounding);
    }
}
