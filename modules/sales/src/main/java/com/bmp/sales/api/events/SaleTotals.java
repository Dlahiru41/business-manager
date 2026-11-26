package com.bmp.sales.api.events;

import java.math.BigDecimal;

/** A bill's totals (SAL-04). */
public record SaleTotals(BigDecimal subtotal, BigDecimal discountTotal, BigDecimal taxTotal, BigDecimal grandTotal) {}
