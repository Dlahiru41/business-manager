package com.bmp.sales.api.events;

import com.bmp.shared.id.PackagingUnitId;
import com.bmp.shared.id.VariantId;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A completed bill's line: base quantity and stock state included, so inventory needs no lookup (SAL-08).
 *
 * @param lineDiscount the cashier's and price rules' discount on the line
 * @param allocatedDiscount the line's share of the bill discount
 */
public record SaleLine(
        UUID lineId,
        VariantId variantId,
        PackagingUnitId packagingUnitId,
        String description,
        BigDecimal qty,
        BigDecimal qtyBase,
        String stockState,
        boolean weighed,
        BigDecimal unitPrice,
        BigDecimal lineDiscount,
        BigDecimal allocatedDiscount,
        BigDecimal tax,
        BigDecimal lineTotal) {}
