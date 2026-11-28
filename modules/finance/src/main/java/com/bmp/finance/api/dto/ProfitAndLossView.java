package com.bmp.finance.api.dto;

import java.math.BigDecimal;

/** Profit and loss as posting-rules.md §4 defines it; every amount in the tenant currency. */
public record ProfitAndLossView(
        String currency,
        BigDecimal revenue,
        BigDecimal returns,
        BigDecimal discounts,
        BigDecimal netRevenue,
        BigDecimal costOfGoodsSold,
        BigDecimal wastage,
        BigDecimal countVariance,
        BigDecimal purchasePriceVariance,
        BigDecimal cashOverShort,
        BigDecimal operatingExpenses,
        BigDecimal profit) {}
