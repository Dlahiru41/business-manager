package com.bmp.sales.domain.model;

import java.math.BigDecimal;

/**
 * A bill's totals (SAL-04).
 *
 * @param subtotal Σ round(qty × unit price)
 * @param discountTotal line discounts plus the bill discount
 * @param taxTotal Σ line taxes
 * @param grandTotal Σ line totals: what the customer pays
 */
public record Totals(BigDecimal subtotal, BigDecimal discountTotal, BigDecimal taxTotal, BigDecimal grandTotal) {

    public static final Totals ZERO = new Totals(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

    /** Whether any total differs from {@code other} by more than {@code tolerance}. */
    public boolean differsFrom(Totals other, BigDecimal tolerance) {
        return beyond(subtotal, other.subtotal, tolerance)
                || beyond(discountTotal, other.discountTotal, tolerance)
                || beyond(taxTotal, other.taxTotal, tolerance)
                || beyond(grandTotal, other.grandTotal, tolerance);
    }

    private static boolean beyond(BigDecimal a, BigDecimal b, BigDecimal tolerance) {
        return a.subtract(b).abs().compareTo(tolerance) > 0;
    }
}
