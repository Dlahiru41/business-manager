package com.bmp.pricing.application;

import com.bmp.pricing.api.PricingQueries;
import com.bmp.pricing.api.dto.BasketQuote;
import com.bmp.pricing.api.dto.QuotedLine;
import com.bmp.pricing.application.query.PricingQueryService;
import com.bmp.pricing.application.query.ResolvePricesService;
import com.bmp.pricing.domain.model.PricingStatus;
import com.bmp.pricing.domain.model.SalesChannel;
import com.bmp.pricing.domain.service.ResolutionRequest;
import com.bmp.pricing.domain.service.ResolvedBasket;
import com.bmp.pricing.domain.service.ResolvedLine;
import com.bmp.web.pagination.PageQuery;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** {@link PricingQueries} over the same resolution as {@code POST /prices:resolve}. */
@Component
class PricingQueriesImpl implements PricingQueries {

    /** More lists, categories or rates than a tenant has. */
    private static final int ALL = 200;

    private final ResolvePricesService resolver;
    private final PricingQueryService pricing;

    PricingQueriesImpl(ResolvePricesService resolver, PricingQueryService pricing) {
        this.resolver = resolver;
        this.pricing = pricing;
    }

    @Override
    @Transactional(readOnly = true)
    public Object tillPriceLists() {
        return pricing.priceLists(null, PricingStatus.ACTIVE, PageQuery.of(ALL, null)).items().stream()
                .map(l -> pricing.snapshot(l.id(), null))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Object tillTaxRates() {
        return Map.of(
                "categories", pricing.taxCategories(PageQuery.of(ALL, null)).items(),
                "rates", pricing.taxRates(null, PageQuery.of(ALL, null)).items());
    }

    @Override
    @Transactional(readOnly = true)
    public List<QuotedLine> quote(BasketQuote basket) {
        ResolvedBasket resolved = resolver.resolve(new ResolvePricesService.Basket(
                SalesChannel.valueOf(basket.channel()),
                basket.customerId(),
                basket.customerGroupId(),
                basket.at(),
                basket.lines().stream()
                        .map(l -> new ResolutionRequest.Line(l.lineId(), l.variantId(), l.packagingUnitId(), l.qty()))
                        .toList()));
        return resolved.lines().stream().map(PricingQueriesImpl::quoted).toList();
    }

    private static QuotedLine quoted(ResolvedLine line) {
        return new QuotedLine(
                line.lineId() != null ? line.lineId() : "",
                line.status().name(),
                line.unitPrice() == null ? null : line.unitPrice().amount(),
                line.discount() == null ? BigDecimal.ZERO : line.discount().amount(),
                Boolean.TRUE.equals(line.taxInclusive()),
                line.priceListId(),
                line.priceListVersion(),
                line.source(),
                line.taxes().stream()
                        .map(t -> new QuotedLine.Rate(t.taxRateId(), t.code(), t.ratePct(), t.compound()))
                        .toList());
    }
}
