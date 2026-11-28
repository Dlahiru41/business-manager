package com.bmp.reporting.domain.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/** Margin and margin percentage as the reports show them (posting-rules.md §4). */
public final class Margins {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private Margins() {}

    public static BigDecimal margin(BigDecimal revenue, BigDecimal cost) {
        return revenue.subtract(cost);
    }

    /** margin / revenue × 100 with two decimals; empty when there is no revenue. */
    public static Optional<BigDecimal> percent(BigDecimal revenue, BigDecimal cost) {
        if (revenue.signum() == 0) {
            return Optional.empty();
        }
        return Optional.of(margin(revenue, cost).multiply(HUNDRED).divide(revenue, 2, RoundingMode.HALF_UP));
    }
}
