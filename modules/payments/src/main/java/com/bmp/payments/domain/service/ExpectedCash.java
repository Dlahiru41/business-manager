package com.bmp.payments.domain.service;

import com.bmp.payments.domain.model.CashMovement;
import java.math.BigDecimal;
import java.util.Collection;

/**
 * Rule PAY-04: {@code expected = float + sales − refunds − paidOut + paidIn − drops}, from a drawer's cash movements.
 */
public record ExpectedCash(
        BigDecimal openingFloat,
        BigDecimal sales,
        BigDecimal refunds,
        BigDecimal paidOut,
        BigDecimal paidIn,
        BigDecimal drops) {

    public static ExpectedCash of(Collection<CashMovement> movements) {
        BigDecimal[] sums = {
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
        };
        for (CashMovement m : movements) {
            int slot = switch (m.type()) {
                case FLOAT -> 0;
                case SALE -> 1;
                case REFUND -> 2;
                case PAID_OUT -> 3;
                case PAID_IN -> 4;
                case DROP -> 5;
            };
            sums[slot] = sums[slot].add(m.amount());
        }
        return new ExpectedCash(sums[0], sums[1], sums[2], sums[3], sums[4], sums[5]);
    }

    public BigDecimal expected() {
        return openingFloat
                .add(sales)
                .subtract(refunds)
                .subtract(paidOut)
                .add(paidIn)
                .subtract(drops);
    }
}
