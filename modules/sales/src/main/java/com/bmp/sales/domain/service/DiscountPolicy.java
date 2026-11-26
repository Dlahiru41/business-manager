package com.bmp.sales.domain.service;

import com.bmp.sales.domain.error.SalesErrorCode;
import com.bmp.sales.domain.model.BillFlag;
import com.bmp.sales.domain.model.Mode;
import com.bmp.shared.error.BusinessRuleViolation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;

/**
 * Rule SAL-05: discounts above {@code maxCashierDiscountPct} of the subtotal need the approval of a user with
 * {@code sales:discount-unlimited}. Online they are refused without it; offline they are accepted and flagged.
 */
public final class DiscountPolicy {

    private DiscountPolicy() {}

    /** The flag an offline bill gets, if any; throws for an online bill over the limit without approval. */
    public static Optional<BillFlag> check(
            BigDecimal discountTotal, BigDecimal subtotal, BigDecimal maxPct, boolean approved, Mode mode) {
        if (approved || !exceeds(discountTotal, subtotal, maxPct)) {
            return Optional.empty();
        }
        if (mode.online()) {
            throw new BusinessRuleViolation(
                    SalesErrorCode.DISCOUNT_LIMIT_EXCEEDED,
                    Map.of("maxPct", maxPct.stripTrailingZeros().toPlainString()));
        }
        return Optional.of(BillFlag.DISCOUNT_LIMIT_EXCEEDED);
    }

    static boolean exceeds(BigDecimal discountTotal, BigDecimal subtotal, BigDecimal maxPct) {
        if (discountTotal.signum() == 0) {
            return false;
        }
        if (subtotal.signum() == 0) {
            return true;
        }
        BigDecimal pct = discountTotal.multiply(new BigDecimal("100")).divide(subtotal, 6, RoundingMode.HALF_UP);
        return pct.compareTo(maxPct) > 0;
    }
}
