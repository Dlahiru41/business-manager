package com.bmp.shared.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * How money is rounded. Amounts are computed at full precision and rounded at line level; bill totals are sums of
 * rounded lines (02-coding-standards.md §5). The policy is a tenant setting ({@code sales.rounding}).
 */
public enum RoundingPolicy {
    /** Round each line to two decimals, half up. The default. */
    LINE_HALF_UP_2(RoundingMode.HALF_UP, 2);

    private final RoundingMode mode;
    private final int scale;

    RoundingPolicy(RoundingMode mode, int scale) {
        this.mode = mode;
        this.scale = scale;
    }

    public static RoundingPolicy defaultPolicy() {
        return LINE_HALF_UP_2;
    }

    public RoundingMode mode() {
        return mode;
    }

    public int scale() {
        return scale;
    }

    /** Rounds an amount to this policy's scale. */
    public BigDecimal round(BigDecimal amount) {
        return amount.setScale(scale, mode);
    }
}
