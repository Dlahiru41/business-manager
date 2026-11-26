package com.bmp.sales.domain.model;

import com.bmp.shared.money.TaxSplit;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A bill line before its totals: what was sold at which price, as the replica of the catalog and pricing say.
 *
 * @param qty in the packaging unit
 * @param qtyBase in the variant's base unit (SAL-08)
 * @param unitPrice per packaging unit
 * @param lineDiscount an amount off this line: the cashier's discount plus {@code ruleDiscount}
 * @param ruleDiscount what price rules took off (quantity breaks, promotions); not a cashier discount (SAL-05)
 * @param taxInclusive whether the price includes tax (its price list's setting)
 * @param rates the tax rates of the variant's category on the business date
 */
public record LineDraft(
        UUID id,
        UUID variantId,
        UUID packagingUnitId,
        String description,
        BigDecimal qty,
        BigDecimal qtyBase,
        String stockState,
        boolean weighed,
        BigDecimal unitPrice,
        BigDecimal lineDiscount,
        BigDecimal ruleDiscount,
        @Nullable String priceSource,
        boolean taxInclusive,
        List<TaxSplit.Rate> rates) {

    public LineDraft {
        rates = List.copyOf(rates);
    }
}
