package com.bmp.pricing.domain.service;

import com.bmp.shared.money.Money;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One tax of a priced line, rounded per line (PRC-07).
 *
 * @param taxRateId the rate applied
 * @param code the rate code, e.g. {@code VAT}
 * @param ratePct the percentage
 * @param amount the tax of the line
 * @param compound whether the rate applies to the net plus the taxes before it
 */
public record LineTax(UUID taxRateId, String code, BigDecimal ratePct, Money amount, boolean compound) {}
