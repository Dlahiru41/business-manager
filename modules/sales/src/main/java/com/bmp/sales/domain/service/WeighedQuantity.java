package com.bmp.sales.domain.service;

import com.bmp.sales.domain.error.SalesErrorCode;
import com.bmp.shared.error.BusinessRuleViolation;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Rule SAL-15: a weighed line is in grams or kilograms of a variant sold by weight, with at most 3 decimals in
 * kilograms (whole grams).
 */
public final class WeighedQuantity {

    private static final int KG_DECIMALS = 3;

    private WeighedQuantity() {}

    /**
     * Checks a line's quantity.
     *
     * @param weighable whether the variant is sold by weight
     * @param qtyBase the quantity in the base unit
     * @param baseUnit the variant's base unit code
     */
    public static void check(
            UUID lineId, boolean weighed, boolean weighable, BigDecimal qty, BigDecimal qtyBase, String baseUnit) {
        if (!weighed) {
            return;
        }
        boolean grams = "g".equals(baseUnit);
        boolean wholeGrams = !grams || qtyBase.stripTrailingZeros().scale() <= 0;
        if (!weighable || !wholeGrams || qty.stripTrailingZeros().scale() > KG_DECIMALS) {
            throw new BusinessRuleViolation(SalesErrorCode.WEIGHED_QTY_INVALID, Map.of("lineId", lineId));
        }
    }
}
