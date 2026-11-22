package com.bmp.pricing.domain.service;

import com.bmp.pricing.domain.model.Price;
import com.bmp.pricing.domain.model.PriceList;
import com.bmp.pricing.domain.model.PriceRule;
import com.bmp.pricing.domain.model.TaxRate;
import com.bmp.pricing.domain.model.VariantRef;
import com.bmp.shared.money.CurrencyCode;
import com.bmp.shared.money.RoundingPolicy;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Everything price resolution reads, loaded beforehand, so the algorithm itself touches no database (T01-05).
 *
 * @param currency the tenant currency
 * @param rounding the line rounding policy
 * @param derivePriceFromBase tenant setting {@code pricing.derivePriceFromBase} (PRC-05)
 * @param lists the tenant's price lists
 * @param prices the prices of those lists for the basket's units
 * @param variants the replicas of the basket's variants
 * @param rules the tenant's price rules
 * @param taxRates the rates of the basket's tax categories
 */
public record PricingData(
        CurrencyCode currency,
        RoundingPolicy rounding,
        boolean derivePriceFromBase,
        List<PriceList> lists,
        List<Price> prices,
        Map<UUID, VariantRef> variants,
        List<PriceRule> rules,
        List<TaxRate> taxRates) {

    public PricingData {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(rounding, "rounding");
        lists = List.copyOf(lists);
        prices = List.copyOf(prices);
        variants = Map.copyOf(variants);
        rules = List.copyOf(rules);
        taxRates = List.copyOf(taxRates);
    }
}
