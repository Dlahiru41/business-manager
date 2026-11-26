package com.bmp.pricing.api.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A priced line (PRC-04..PRC-07).
 *
 * @param status {@code PRICED}, {@code PRICE_NOT_FOUND} or {@code NOT_SELLABLE}
 * @param unitPrice per packaging unit, from the chosen list (inclusive of tax when {@code taxInclusive})
 * @param ruleDiscount what price rules take off the line
 * @param rates the tax rates of the variant's category on the date
 */
public record QuotedLine(
        String lineId,
        String status,
        @Nullable BigDecimal unitPrice,
        BigDecimal ruleDiscount,
        boolean taxInclusive,
        @Nullable UUID priceListId,
        @Nullable Integer priceListVersion,
        @Nullable String source,
        List<Rate> rates) {

    public QuotedLine {
        rates = List.copyOf(rates);
    }

    public boolean priced() {
        return "PRICED".equals(status);
    }

    /** A tax rate; compound rates apply to the net plus the taxes before them. */
    public record Rate(UUID id, String code, BigDecimal ratePct, boolean compound) {}
}
