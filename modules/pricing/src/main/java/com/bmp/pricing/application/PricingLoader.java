package com.bmp.pricing.application;

import com.bmp.pricing.domain.error.PricingErrorCode;
import com.bmp.pricing.domain.model.PriceList;
import com.bmp.pricing.domain.model.PriceRule;
import com.bmp.pricing.domain.model.TaxCategory;
import com.bmp.pricing.domain.model.TaxRate;
import com.bmp.pricing.domain.port.PriceListRepository;
import com.bmp.pricing.domain.port.PriceRuleRepository;
import com.bmp.pricing.domain.port.TaxCategoryRepository;
import com.bmp.pricing.domain.port.TaxRateRepository;
import com.bmp.shared.error.NotFoundException;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Loads pricing aggregates of the current tenant, or answers 404 (also for other tenants' ids). */
@Component
public class PricingLoader {

    private final PriceListRepository lists;
    private final PriceRuleRepository rules;
    private final TaxCategoryRepository categories;
    private final TaxRateRepository rates;

    public PricingLoader(
            PriceListRepository lists,
            PriceRuleRepository rules,
            TaxCategoryRepository categories,
            TaxRateRepository rates) {
        this.lists = lists;
        this.rules = rules;
        this.categories = categories;
        this.rates = rates;
    }

    public PriceList list(UUID id) {
        return lists.findById(id).orElseThrow(() -> listNotFound(id));
    }

    /** The list, locked for a price change. */
    public PriceList lockedList(UUID id) {
        return lists.lockById(id).orElseThrow(() -> listNotFound(id));
    }

    public PriceRule rule(UUID id) {
        return rules.findById(id)
                .orElseThrow(() -> new NotFoundException(PricingErrorCode.PRICE_RULE_NOT_FOUND, Map.of("ruleId", id)));
    }

    public TaxCategory category(UUID id) {
        return categories
                .findById(id)
                .orElseThrow(() ->
                        new NotFoundException(PricingErrorCode.TAX_CATEGORY_NOT_FOUND, Map.of("taxCategoryId", id)));
    }

    public TaxRate rate(UUID id) {
        return rates.findRate(id)
                .orElseThrow(() -> new NotFoundException(PricingErrorCode.TAX_RATE_NOT_FOUND, Map.of("taxRateId", id)));
    }

    private static NotFoundException listNotFound(UUID id) {
        return new NotFoundException(PricingErrorCode.PRICE_LIST_NOT_FOUND, Map.of("priceListId", id));
    }
}
