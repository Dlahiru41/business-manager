package com.bmp.sales.domain.service;

import com.bmp.shared.money.RoundingPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Rule SAL-10: a returned quantity is worth its pro-rata share of the line's final total (allocated discount and tax
 * included). Returning the last of a line takes what is left of its total, so all returns of a line add up to it
 * exactly.
 */
public final class ReturnAmounts {

    private ReturnAmounts() {}

    /** A returned quantity's amount and the tax in it. */
    public record Share(BigDecimal amount, BigDecimal tax) {}

    /**
     * The share of {@code qty} of a line.
     *
     * @param sold the line's quantity
     * @param returned already returned before
     * @param total the line total
     * @param tax the line's tax
     * @param returnedAmount the amount of earlier returns
     * @param returnedTax the tax of earlier returns
     */
    @SuppressWarnings("TooManyParameters") // a line's figures and its returns so far
    public static Share of(
            BigDecimal qty,
            BigDecimal sold,
            BigDecimal returned,
            BigDecimal total,
            BigDecimal tax,
            BigDecimal returnedAmount,
            BigDecimal returnedTax,
            RoundingPolicy rounding) {
        if (returned.add(qty).compareTo(sold) == 0) {
            return new Share(total.subtract(returnedAmount), tax.subtract(returnedTax));
        }
        return new Share(
                rounding.round(total.multiply(qty).divide(sold, 10, RoundingMode.HALF_UP)),
                rounding.round(tax.multiply(qty).divide(sold, 10, RoundingMode.HALF_UP)));
    }
}
