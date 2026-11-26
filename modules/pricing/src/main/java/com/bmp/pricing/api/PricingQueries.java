package com.bmp.pricing.api;

import com.bmp.pricing.api.dto.BasketQuote;
import com.bmp.pricing.api.dto.QuotedLine;
import java.util.List;

/**
 * Prices a basket for another module (sales re-prices online bills and checks offline ones, SAL-03). Lines without a
 * price come back with their status instead of failing the basket (PRC-05).
 */
public interface PricingQueries {

    List<QuotedLine> quote(BasketQuote basket);
}
