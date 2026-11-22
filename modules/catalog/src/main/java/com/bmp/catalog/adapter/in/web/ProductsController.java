package com.bmp.catalog.adapter.in.web;

import com.bmp.catalog.adapter.in.web.generated.api.ProductsApi;
import com.bmp.catalog.adapter.in.web.generated.model.CreateProductRequest;
import com.bmp.catalog.adapter.in.web.generated.model.GenerateVariantsRequest;
import com.bmp.catalog.adapter.in.web.generated.model.GenerateVariantsResult;
import com.bmp.catalog.adapter.in.web.generated.model.Product;
import com.bmp.catalog.adapter.in.web.generated.model.ProductPage;
import com.bmp.catalog.adapter.in.web.generated.model.ProductTemplateCode;
import com.bmp.catalog.adapter.in.web.generated.model.ReplaceConversionsRequest;
import com.bmp.catalog.adapter.in.web.generated.model.Status;
import com.bmp.catalog.adapter.in.web.generated.model.UpdateProductRequest;
import com.bmp.catalog.application.command.CreateProductHandler;
import com.bmp.catalog.application.command.GenerateVariantsHandler;
import com.bmp.catalog.application.command.ProductHandlers;
import com.bmp.catalog.application.command.ReplaceConversionsHandler;
import com.bmp.catalog.application.query.CatalogQueryService;
import com.bmp.catalog.application.query.ProductView;
import com.bmp.catalog.domain.model.ProductTemplate;
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

/** Products: creation from templates, changes, conversions and variant generation. */
@RestController
class ProductsController implements ProductsApi {

    private final CatalogQueryService queries;
    private final CreateProductHandler create;
    private final ProductHandlers products;
    private final ReplaceConversionsHandler conversions;
    private final GenerateVariantsHandler generate;
    private final CatalogRequests requests;
    private final CatalogWebMapper mapper;

    ProductsController(
            CatalogQueryService queries,
            CreateProductHandler create,
            ProductHandlers products,
            ReplaceConversionsHandler conversions,
            GenerateVariantsHandler generate,
            CatalogRequests requests,
            CatalogWebMapper mapper) {
        this.queries = queries;
        this.create = create;
        this.products = products;
        this.conversions = conversions;
        this.generate = generate;
        this.requests = requests;
        this.mapper = mapper;
    }

    @Override
    @PreAuthorize("hasPermission(null, 'catalog:read')")
    public ResponseEntity<ProductPage> listProducts(
            @Nullable String q,
            @Nullable UUID categoryId,
            @Nullable Status status,
            @Nullable ProductTemplateCode template,
            @Nullable Integer limit,
            @Nullable String cursor) {
        PageResponse<ProductView> page = queries.products(
                q,
                categoryId,
                CatalogRequests.status(status),
                template != null ? ProductTemplate.valueOf(template.getValue()) : null,
                PageQuery.of(limit, cursor));
        return ResponseEntity.ok(new ProductPage()
                .items(page.items().stream().map(mapper::toDto).toList())
                .page(UnitsController.Pages.of(page)));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'catalog:write')")
    public ResponseEntity<Product> createProduct(UUID idempotencyKey, CreateProductRequest request) {
        ProductView created = create.handle(requests.createProduct(request));
        return ResponseEntity.created(URI.create("/api/v1/products/" + created.id()))
                .eTag(Etags.of(created.version()))
                .body(mapper.toDto(created));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'catalog:read')")
    public ResponseEntity<Product> getProduct(UUID productId, @Nullable String include) {
        List<String> parts = include != null ? List.of(include.split(",")) : List.of();
        return respond(queries.product(productId, parts.contains("variants"), parts.contains("units")));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'catalog:write')")
    public ResponseEntity<Product> updateProduct(
            UUID productId, UpdateProductRequest request, @Nullable String ifMatch) {
        return respond(products.update(new ProductHandlers.UpdateProduct(
                productId,
                IfMatch.expectedVersion(ifMatch),
                request.getName(),
                request.getCategoryId(),
                request.getTaxCategoryId(),
                CatalogRequests.tracking(request.getTracking()),
                request.getProperties() != null ? requests.propertiesJson(request.getProperties()) : null)));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'catalog:write')")
    public ResponseEntity<Product> deactivateProduct(UUID productId, UUID idempotencyKey) {
        return respond(products.deactivate(productId));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'catalog:write')")
    public ResponseEntity<Product> reactivateProduct(UUID productId, UUID idempotencyKey) {
        return respond(products.reactivate(productId));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'catalog:write')")
    public ResponseEntity<Product> replaceProductConversions(
            UUID productId, ReplaceConversionsRequest request, @Nullable String ifMatch) {
        return respond(conversions.handle(
                productId, IfMatch.expectedVersion(ifMatch), CatalogRequests.conversions(request.getConversions())));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'catalog:write')")
    public ResponseEntity<GenerateVariantsResult> generateVariants(
            UUID productId, UUID idempotencyKey, GenerateVariantsRequest request) {
        return ResponseEntity.ok(mapper.toDto(
                generate.handle(productId, request.getExclusions(), Boolean.TRUE.equals(request.getDryRun()))));
    }

    private ResponseEntity<Product> respond(ProductView view) {
        return ResponseEntity.ok().eTag(Etags.of(view.version())).body(mapper.toDto(view));
    }
}
