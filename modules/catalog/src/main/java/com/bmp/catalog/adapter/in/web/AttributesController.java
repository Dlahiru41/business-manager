package com.bmp.catalog.adapter.in.web;

import com.bmp.catalog.adapter.in.web.generated.api.AttributesApi;
import com.bmp.catalog.adapter.in.web.generated.model.AttributeType;
import com.bmp.catalog.adapter.in.web.generated.model.AttributeTypeList;
import com.bmp.catalog.adapter.in.web.generated.model.NewAttribute;
import com.bmp.catalog.adapter.in.web.generated.model.UpdateAttributeRequest;
import com.bmp.catalog.application.command.ProductHandlers;
import com.bmp.catalog.application.query.AttributeTypeView;
import com.bmp.catalog.application.query.CatalogQueryService;
import com.bmp.catalog.domain.model.AttributeValueChange;
import com.bmp.web.etag.Etags;
import com.bmp.web.etag.IfMatch;
import com.bmp.web.idempotency.Idempotent;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/** Attribute types of a product; their ETag is the product version. */
@RestController
class AttributesController implements AttributesApi {

    private final CatalogQueryService queries;
    private final ProductHandlers products;
    private final CatalogWebMapper mapper;

    AttributesController(CatalogQueryService queries, ProductHandlers products, CatalogWebMapper mapper) {
        this.queries = queries;
        this.products = products;
        this.mapper = mapper;
    }

    @Override
    @PreAuthorize("hasPermission(null, 'catalog:read')")
    public ResponseEntity<AttributeTypeList> listAttributes(UUID productId) {
        return ResponseEntity.ok(new AttributeTypeList()
                .items(queries.attributes(productId).stream().map(mapper::toDto).toList()));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'catalog:write')")
    public ResponseEntity<AttributeType> createAttribute(UUID productId, UUID idempotencyKey, NewAttribute request) {
        ProductHandlers.AttributeResult created = products.addAttribute(productId, CatalogRequests.attribute(request));
        return ResponseEntity.created(URI.create("/api/v1/products/" + productId + "/attributes/"
                        + created.attribute().id()))
                .eTag(Etags.of(created.productVersion()))
                .body(mapper.toDto(created.attribute()));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'catalog:read')")
    public ResponseEntity<AttributeType> getAttribute(UUID productId, UUID attributeId) {
        CatalogQueryService.AttributeResult found = queries.attribute(productId, attributeId);
        return respond(found.attribute(), found.productVersion());
    }

    @Override
    @PreAuthorize("hasPermission(null, 'catalog:write')")
    public ResponseEntity<AttributeType> updateAttribute(
            UUID productId, UUID attributeId, UpdateAttributeRequest request, @Nullable String ifMatch) {
        List<AttributeValueChange> values = request.getValues() != null
                ? request.getValues().stream()
                        .map(v -> new AttributeValueChange(v.getId(), v.getCode(), v.getValue(), v.getSort()))
                        .toList()
                : List.of();
        ProductHandlers.AttributeResult changed = products.changeAttribute(
                productId, attributeId, IfMatch.expectedVersion(ifMatch), request.getName(), request.getSort(), values);
        return respond(changed.attribute(), changed.productVersion());
    }

    private ResponseEntity<AttributeType> respond(AttributeTypeView view, long productVersion) {
        return ResponseEntity.ok().eTag(Etags.of(productVersion)).body(mapper.toDto(view));
    }
}
