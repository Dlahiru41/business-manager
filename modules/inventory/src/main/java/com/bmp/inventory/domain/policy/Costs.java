package com.bmp.inventory.domain.policy;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/** Arithmetic shared by the costing policies. */
final class Costs {

    static final int UNIT_COST_SCALE = 6;
    private static final MathContext PRECISION = MathContext.DECIMAL128;

    private Costs() {}

    /** {@code value × qty / total} at full precision. */
    static BigDecimal share(BigDecimal value, BigDecimal qty, BigDecimal total) {
        return value.multiply(qty, PRECISION).divide(total, PRECISION);
    }

    static BigDecimal unitCost(BigDecimal value, BigDecimal qty) {
        return qty.signum() == 0 ? BigDecimal.ZERO : value.divide(qty, UNIT_COST_SCALE, RoundingMode.HALF_UP);
    }

    static BigDecimal exactUnitCost(BigDecimal value, BigDecimal qty) {
        return value.divide(qty, PRECISION);
    }
}
