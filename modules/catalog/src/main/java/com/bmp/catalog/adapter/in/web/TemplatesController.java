package com.bmp.catalog.adapter.in.web;

import com.bmp.catalog.adapter.in.web.generated.api.TemplatesApi;
import com.bmp.catalog.adapter.in.web.generated.model.ProductTemplateList;
import com.bmp.catalog.application.query.CatalogQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/** Product templates and their defaults (CAT-09). */
@RestController
class TemplatesController implements TemplatesApi {

    private final CatalogQueryService queries;
    private final CatalogWebMapper mapper;

    TemplatesController(CatalogQueryService queries, CatalogWebMapper mapper) {
        this.queries = queries;
        this.mapper = mapper;
    }

    @Override
    @PreAuthorize("hasPermission(null, 'catalog:read')")
    public ResponseEntity<ProductTemplateList> listProductTemplates() {
        return ResponseEntity.ok(new ProductTemplateList()
                .items(queries.templates().stream().map(mapper::toDto).toList()));
    }
}
