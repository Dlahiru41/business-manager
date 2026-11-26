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

    /**
     * The active price lists with their prices, as a till caches them (SYN-02): records that serialise to pricing.yaml
     * {@code PriceSnapshot}s. Other modules pass them on and never look inside.
     */
    Object tillPriceLists();

    /** Tax categories and their rates, as a till caches them (SYN-02); passed on, never looked into. */
    Object tillTaxRates();
}
