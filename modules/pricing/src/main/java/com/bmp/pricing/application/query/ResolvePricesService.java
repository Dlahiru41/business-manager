package com.bmp.pricing.application.query;

import com.bmp.pricing.application.PricingSettings;
import com.bmp.pricing.domain.model.PriceList;
import com.bmp.pricing.domain.model.VariantRef;
import com.bmp.pricing.domain.port.PriceListRepository;
import com.bmp.pricing.domain.port.PriceRepository;
import com.bmp.pricing.domain.port.PriceRuleRepository;
import com.bmp.pricing.domain.port.TaxRateRepository;
import com.bmp.pricing.domain.port.VariantRefStore;
import com.bmp.pricing.domain.service.PriceResolver;
import com.bmp.pricing.domain.service.PricingData;
import com.bmp.pricing.domain.service.ResolutionRequest;
import com.bmp.pricing.domain.service.ResolvedBasket;
import com.bmp.shared.money.CurrencyCode;
import com.bmp.shared.money.RoundingPolicy;
import com.bmp.tenant.api.dto.TenantSummary;
import io.micrometer.observation.annotation.Observed;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prices a basket (PRC-04..PRC-07): loads the lists, the prices of the basket's units and of their base units, the
 * rules, the variants' replicas and their tax rates, then runs the pure {@link PriceResolver}.
 */
@Service
public class ResolvePricesService {

    private final PriceListRepository lists;
    private final PriceRepository prices;
    private final PriceRuleRepository rules;
    private final TaxRateRepository rates;
    private final VariantRefStore variants;
    private final PricingSettings settings;
    private final Clock clock;

    public ResolvePricesService(
            PriceListRepository lists,
            PriceRepository prices,
            PriceRuleRepository rules,
            TaxRateRepository rates,
            VariantRefStore variants,
            PricingSettings settings,
            Clock clock) {
        this.lists = lists;
        this.prices = prices;
        this.rules = rules;
        this.rates = rates;
        this.variants = variants;
        this.settings = settings;
        this.clock = clock;
    }

    /** A basket to price; {@code at} defaults to now. */
    public record Basket(
            com.bmp.pricing.domain.model.SalesChannel channel,
            @Nullable UUID customerId,
            @Nullable UUID customerGroupId,
            @Nullable Instant at,
            List<ResolutionRequest.Line> lines) {

        public Basket {
            lines = List.copyOf(lines);
        }
    }

    @Transactional(readOnly = true)
    @Observed(name = "bmp.pricing.resolve")
    public ResolvedBasket resolve(Basket basket) {
        Instant at = basket.at() != null ? basket.at() : clock.instant();
        Map<UUID, VariantRef> refs = variants.findAll(basket.lines().stream()
                .map(ResolutionRequest.Line::variantId)
                .distinct()
                .toList());
        Set<UUID> unitIds = new HashSet<>();
        Set<UUID> categories = new HashSet<>();
        for (ResolutionRequest.Line line : basket.lines()) {
            unitIds.add(line.packagingUnitId());
            VariantRef ref = refs.get(line.variantId());
            if (ref != null) {
                ref.baseUnit().ifPresent(base -> unitIds.add(base.id()));
                if (ref.taxCategoryId() != null) {
                    categories.add(Objects.requireNonNull(ref.taxCategoryId()));
                }
            }
        }
        TenantSummary tenant = settings.tenant();
        List<PriceList> all = lists.findAll();
        PricingData data = new PricingData(
                CurrencyCode.of(tenant.currency()),
                RoundingPolicy.defaultPolicy(),
                settings.derivePriceFromBase(),
                all,
                prices.findByListsAndUnits(all.stream().map(PriceList::id).toList(), unitIds),
                refs,
                rules.findAll(),
                rates.findByCategories(categories));
        ResolutionRequest request = new ResolutionRequest(
                basket.channel(),
                basket.customerId(),
                basket.customerGroupId(),
                at,
                at.atZone(PricingSettings.zone(tenant)).toLocalDate(),
                basket.lines());
        return PriceResolver.resolve(request, data);
    }
}
