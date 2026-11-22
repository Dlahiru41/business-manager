package com.bmp.pricing.adapter.in.web;

import com.bmp.pricing.adapter.in.web.generated.api.TaxesApi;
import com.bmp.pricing.adapter.in.web.generated.model.CreateTaxCategoryRequest;
import com.bmp.pricing.adapter.in.web.generated.model.CreateTaxRateRequest;
import com.bmp.pricing.adapter.in.web.generated.model.TaxCategory;
import com.bmp.pricing.adapter.in.web.generated.model.TaxCategoryPage;
import com.bmp.pricing.adapter.in.web.generated.model.TaxRate;
import com.bmp.pricing.adapter.in.web.generated.model.TaxRatePage;
import com.bmp.pricing.adapter.in.web.generated.model.UpdateTaxCategoryRequest;
import com.bmp.pricing.adapter.in.web.generated.model.UpdateTaxRateRequest;
import com.bmp.pricing.application.command.TaxHandlers;
import com.bmp.pricing.application.query.PricingQueryService;
import com.bmp.pricing.application.query.TaxCategoryView;
import com.bmp.pricing.application.query.TaxRateView;
import com.bmp.web.etag.Etags;
import com.bmp.web.etag.IfMatch;
import com.bmp.web.idempotency.Idempotent;
import com.bmp.web.pagination.PageQuery;
import com.bmp.web.pagination.PageResponse;
import java.math.BigDecimal;
import java.net.URI;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/** Tax categories and rates (PRC-07). */
@RestController
class TaxesController implements TaxesApi {

    private final PricingQueryService queries;
    private final TaxHandlers handlers;
    private final PricingWebMapper mapper;

    TaxesController(PricingQueryService queries, TaxHandlers handlers, PricingWebMapper mapper) {
        this.queries = queries;
        this.handlers = handlers;
        this.mapper = mapper;
    }

    @Override
    @PreAuthorize("hasPermission(null, 'pricing:write')")
    public ResponseEntity<TaxCategoryPage> listTaxCategories(@Nullable Integer limit, @Nullable String cursor) {
        PageResponse<TaxCategoryView> page = queries.taxCategories(PageQuery.of(limit, cursor));
        return ResponseEntity.ok(new TaxCategoryPage()
                .items(page.items().stream().map(mapper::toDto).toList())
                .page(PriceListsController.page(page)));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'pricing:write')")
    public ResponseEntity<TaxCategory> createTaxCategory(UUID idempotencyKey, CreateTaxCategoryRequest request) {
        TaxCategoryView created = handlers.createCategory(request.getCode(), request.getName());
        return ResponseEntity.created(URI.create("/api/v1/tax-categories/" + created.id()))
                .eTag(Etags.of(created.version()))
                .body(mapper.toDto(created));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'pricing:write')")
    public ResponseEntity<TaxCategory> getTaxCategory(UUID taxCategoryId) {
        TaxCategoryView view = queries.taxCategory(taxCategoryId);
        return ResponseEntity.ok().eTag(Etags.of(view.version())).body(mapper.toDto(view));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'pricing:write')")
    public ResponseEntity<TaxCategory> updateTaxCategory(
            UUID taxCategoryId, UpdateTaxCategoryRequest request, @Nullable String ifMatch) {
        TaxCategoryView view = handlers.updateCategory(
                taxCategoryId,
                IfMatch.expectedVersion(ifMatch),
                request.getName(),
                PricingRequests.status(request.getStatus()));
        return ResponseEntity.ok().eTag(Etags.of(view.version())).body(mapper.toDto(view));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'pricing:write')")
    public ResponseEntity<TaxRatePage> listTaxRates(
            @Nullable UUID taxCategoryId, @Nullable Integer limit, @Nullable String cursor) {
        PageResponse<TaxRateView> page = queries.taxRates(taxCategoryId, PageQuery.of(limit, cursor));
        return ResponseEntity.ok(new TaxRatePage()
                .items(page.items().stream().map(mapper::toDto).toList())
                .page(PriceListsController.page(page)));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'pricing:write')")
    public ResponseEntity<TaxRate> createTaxRate(UUID idempotencyKey, CreateTaxRateRequest request) {
        TaxRateView created = handlers.createRate(new TaxHandlers.CreateRate(
                request.getTaxCategoryId(),
                request.getCode(),
                request.getName(),
                new BigDecimal(request.getRatePct()),
                Boolean.TRUE.equals(request.getCompound()),
                request.getValidFrom(),
                request.getValidTo()));
        return ResponseEntity.created(URI.create("/api/v1/tax-rates/" + created.id()))
                .eTag(Etags.of(created.version()))
                .body(mapper.toDto(created));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'pricing:write')")
    public ResponseEntity<TaxRate> getTaxRate(UUID taxRateId) {
        TaxRateView view = queries.taxRate(taxRateId);
        return ResponseEntity.ok().eTag(Etags.of(view.version())).body(mapper.toDto(view));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'pricing:write')")
    public ResponseEntity<TaxRate> updateTaxRate(
            UUID taxRateId, UpdateTaxRateRequest request, @Nullable String ifMatch) {
        TaxRateView view = handlers.updateRate(
                taxRateId, IfMatch.expectedVersion(ifMatch), request.getName(), request.getValidTo());
        return ResponseEntity.ok().eTag(Etags.of(view.version())).body(mapper.toDto(view));
    }
}
