package com.bmp.catalog.adapter.in.web;

import com.bmp.catalog.adapter.in.web.generated.api.VariantsApi;
import com.bmp.catalog.adapter.in.web.generated.model.CreateVariantRequest;
import com.bmp.catalog.adapter.in.web.generated.model.Status;
import com.bmp.catalog.adapter.in.web.generated.model.UpdateVariantRequest;
import com.bmp.catalog.adapter.in.web.generated.model.Variant;
import com.bmp.catalog.adapter.in.web.generated.model.VariantLookup;
import com.bmp.catalog.adapter.in.web.generated.model.VariantPage;
import com.bmp.catalog.application.command.VariantHandlers;
import com.bmp.catalog.application.query.CatalogQueryService;
import com.bmp.catalog.application.query.VariantView;
import com.bmp.web.etag.Etags;
import com.bmp.web.etag.IfMatch;
import com.bmp.web.idempotency.Idempotent;
import com.bmp.web.pagination.PageQuery;
import com.bmp.web.pagination.PageResponse;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/** Variants (SKUs): lists, lookups by barcode or SKU, manual creation and changes. */
@RestController
class VariantsController implements VariantsApi {

    private final CatalogQueryService queries;
    private final VariantHandlers handlers;
    private final CatalogRequests requests;
    private final CatalogWebMapper mapper;

    VariantsController(
            CatalogQueryService queries, VariantHandlers handlers, CatalogRequests requests, CatalogWebMapper mapper) {
        this.queries = queries;
        this.handlers = handlers;
        this.requests = requests;
        this.mapper = mapper;
    }

    @Override
    @PreAuthorize("hasPermission(null, 'catalog:read')")
    public ResponseEntity<VariantPage> listProductVariants(
            UUID productId, @Nullable Integer limit, @Nullable String cursor) {
        return page(queries.productVariants(productId, PageQuery.of(limit, cursor)));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'catalog:read')")
    public ResponseEntity<VariantPage> listVariants(
            @Nullable Boolean sellable,
            @Nullable Boolean purchasable,
            @Nullable String q,
            @Nullable UUID productId,
            @Nullable Status status,
            @Nullable Integer limit,
            @Nullable String cursor) {
        return page(queries.variants(
                sellable, purchasable, q, productId, CatalogRequests.status(status), PageQuery.of(limit, cursor)));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'catalog:read')")
    public ResponseEntity<VariantLookup> lookupVariant(@Nullable String barcode, @Nullable String sku) {
        CatalogQueryService.LookupResult found = queries.lookup(barcode, sku);
        return ResponseEntity.ok(
                new VariantLookup().variant(mapper.toDto(found.variant())).packagingUnitId(found.packagingUnitId()));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'catalog:write')")
    public ResponseEntity<Variant> createVariant(UUID productId, UUID idempotencyKey, CreateVariantRequest request) {
        VariantView created = handlers.create(requests.createVariant(productId, request));
        return ResponseEntity.created(URI.create("/api/v1/products/" + productId + "/variants/" + created.id()))
                .eTag(Etags.of(created.version()))
                .body(mapper.toDto(created));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'catalog:read')")
    public ResponseEntity<Variant> getVariant(UUID productId, UUID variantId) {
        return respond(queries.variant(productId, variantId));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'catalog:write')")
    public ResponseEntity<Variant> updateVariant(
            UUID productId, UUID variantId, UpdateVariantRequest request, @Nullable String ifMatch) {
        List<String> states = request.getStockStates();
        return respond(handlers.update(new VariantHandlers.UpdateVariant(
                productId,
                variantId,
                IfMatch.expectedVersion(ifMatch),
                request.getName(),
                request.getSku(),
                states != null ? List.copyOf(states) : null,
                CatalogRequests.sellMode(request.getSellMode()))));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'catalog:write')")
    public ResponseEntity<Variant> deactivateVariant(UUID variantId, UUID idempotencyKey) {
        return respond(handlers.deactivate(variantId));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'catalog:write')")
    public ResponseEntity<Variant> reactivateVariant(UUID variantId, UUID idempotencyKey) {
        return respond(handlers.reactivate(variantId));
    }

    private ResponseEntity<VariantPage> page(PageResponse<VariantView> page) {
        return ResponseEntity.ok(new VariantPage()
                .items(page.items().stream().map(mapper::toDto).toList())
                .page(UnitsController.Pages.of(page)));
    }

    private ResponseEntity<Variant> respond(VariantView view) {
        return ResponseEntity.ok().eTag(Etags.of(view.version())).body(mapper.toDto(view));
    }
}
