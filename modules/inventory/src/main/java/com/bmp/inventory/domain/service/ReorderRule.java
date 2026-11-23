package com.bmp.inventory.domain.service;

import java.math.BigDecimal;

/** Rule INV-13: one alert per crossing of the reorder level. */
public final class ReorderRule {

    private ReorderRule() {}

    /** What a change of available stock does to a reorder policy's low flag. */
    public enum Decision {
        RAISE,
        CLEAR,
        KEEP
    }

    /**
     * What happens to the low flag when available stock changes.
     *
     * @param outbound whether the change took stock out (only out-movements raise the alert)
     */
    public static Decision decide(BigDecimal available, BigDecimal reorderLevel, boolean lowFlag, boolean outbound) {
        boolean low = available.compareTo(reorderLevel) <= 0;
        if (low && !lowFlag && outbound) {
            return Decision.RAISE;
        }
        if (!low && lowFlag) {
            return Decision.CLEAR;
        }
        return Decision.KEEP;
    }
}
