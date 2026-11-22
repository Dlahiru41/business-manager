package com.bmp.pricing.application.command;

import com.bmp.pricing.application.PricingLoader;
import com.bmp.pricing.application.query.TaxCategoryView;
import com.bmp.pricing.application.query.TaxRateView;
import com.bmp.pricing.domain.error.PricingErrorCode;
import com.bmp.pricing.domain.model.PricingStatus;
import com.bmp.pricing.domain.model.TaxCategory;
import com.bmp.pricing.domain.model.TaxRate;
import com.bmp.pricing.domain.port.TaxCategoryRepository;
import com.bmp.pricing.domain.port.TaxRateRepository;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.id.IdGenerator;
import com.bmp.web.etag.IfMatch;
import io.micrometer.observation.annotation.Observed;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tax categories and their dated rates (PRC-07); rates of one category and code never overlap. */
@Service
public class TaxHandlers {

    private final TaxCategoryRepository categories;
    private final TaxRateRepository rates;
    private final PricingLoader loader;
    private final IdGenerator ids;

    public TaxHandlers(
            TaxCategoryRepository categories, TaxRateRepository rates, PricingLoader loader, IdGenerator ids) {
        this.categories = categories;
        this.rates = rates;
        this.loader = loader;
        this.ids = ids;
    }

    public record CreateRate(
            UUID taxCategoryId,
            String code,
            String name,
            BigDecimal ratePct,
            boolean compound,
            LocalDate validFrom,
            @Nullable LocalDate validTo) {}

    @Transactional
    @Observed(name = "bmp.pricing.create-tax-category")
    public TaxCategoryView createCategory(String code, String name) {
        return TaxCategoryView.of(
                categories.saveAndFlush(TaxCategory.create(ids.newUuid(), TenantContext.require(), code, name)));
    }

    @Transactional
    @Observed(name = "bmp.pricing.update-tax-category")
    public TaxCategoryView updateCategory(
            UUID id, long expectedVersion, @Nullable String name, @Nullable PricingStatus status) {
        TaxCategory category = loader.category(id);
        IfMatch.check(expectedVersion, category.version());
        if (name != null) {
            category.rename(name);
        }
        if (status == PricingStatus.INACTIVE) {
            category.deactivate();
        } else if (status == PricingStatus.ACTIVE) {
            category.activate();
        }
        return TaxCategoryView.of(categories.saveAndFlush(category));
    }

    @Transactional
    @Observed(name = "bmp.pricing.create-tax-rate")
    public TaxRateView createRate(CreateRate command) {
        if (categories.findById(command.taxCategoryId()).isEmpty()) {
            throw new BusinessRuleViolation(
                    PricingErrorCode.UNKNOWN_TAX_CATEGORY, Map.of("taxCategoryId", command.taxCategoryId()));
        }
        TaxRate rate = TaxRate.create(
                ids.newUuid(),
                TenantContext.require(),
                command.taxCategoryId(),
                command.code(),
                command.name(),
                command.ratePct(),
                command.compound(),
                command.validFrom(),
                command.validTo());
        requireNoOverlap(rate);
        return TaxRateView.of(rates.saveAndFlush(rate));
    }

    @Transactional
    @Observed(name = "bmp.pricing.update-tax-rate")
    public TaxRateView updateRate(UUID id, long expectedVersion, @Nullable String name, @Nullable LocalDate validTo) {
        TaxRate rate = loader.rate(id);
        IfMatch.check(expectedVersion, rate.version());
        if (name != null) {
            rate.rename(name);
        }
        if (validTo != null) {
            rate.endOn(validTo);
            requireNoOverlap(rate);
        }
        return TaxRateView.of(rates.saveAndFlush(rate));
    }

    private void requireNoOverlap(TaxRate rate) {
        List<TaxRate> siblings = rates.findByCategories(List.of(rate.taxCategoryId()));
        if (siblings.stream().anyMatch(rate::overlaps)) {
            throw new BusinessRuleViolation(
                    PricingErrorCode.TAX_RATE_OVERLAP,
                    Map.of("code", rate.code(), "validFrom", rate.validFrom().toString()));
        }
    }
}
