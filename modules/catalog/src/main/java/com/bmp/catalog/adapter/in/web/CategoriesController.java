package com.bmp.catalog.adapter.in.web;

import com.bmp.catalog.adapter.in.web.generated.api.CategoriesApi;
import com.bmp.catalog.adapter.in.web.generated.model.Category;
import com.bmp.catalog.adapter.in.web.generated.model.CategoryPage;
import com.bmp.catalog.adapter.in.web.generated.model.CreateCategoryRequest;
import com.bmp.catalog.adapter.in.web.generated.model.UpdateCategoryRequest;
import com.bmp.catalog.application.command.CategoryHandlers;
import com.bmp.catalog.application.query.CatalogQueryService;
import com.bmp.catalog.application.query.CategoryView;
import com.bmp.web.etag.Etags;
import com.bmp.web.etag.IfMatch;
import com.bmp.web.idempotency.Idempotent;
import com.bmp.web.pagination.PageQuery;
import com.bmp.web.pagination.PageResponse;
import java.net.URI;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/** Product categories. */
@RestController
class CategoriesController implements CategoriesApi {

    private final CatalogQueryService queries;
    private final CategoryHandlers handlers;
    private final CatalogWebMapper mapper;

    CategoriesController(CatalogQueryService queries, CategoryHandlers handlers, CatalogWebMapper mapper) {
        this.queries = queries;
        this.handlers = handlers;
        this.mapper = mapper;
    }

    @Override
    @PreAuthorize("hasPermission(null, 'catalog:read')")
    public ResponseEntity<CategoryPage> listCategories(
            @Nullable UUID parentId, @Nullable Integer limit, @Nullable String cursor) {
        PageResponse<CategoryView> page = queries.categories(parentId, PageQuery.of(limit, cursor));
        return ResponseEntity.ok(new CategoryPage()
                .items(page.items().stream().map(mapper::toDto).toList())
                .page(UnitsController.Pages.of(page)));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'catalog:write')")
    public ResponseEntity<Category> createCategory(UUID idempotencyKey, CreateCategoryRequest request) {
        CategoryView created = handlers.create(request.getName(), request.getParentId());
        return ResponseEntity.created(URI.create("/api/v1/categories/" + created.id()))
                .eTag(Etags.of(created.version()))
                .body(mapper.toDto(created));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'catalog:read')")
    public ResponseEntity<Category> getCategory(UUID categoryId) {
        return respond(queries.category(categoryId));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'catalog:write')")
    public ResponseEntity<Category> updateCategory(
            UUID categoryId, UpdateCategoryRequest request, @Nullable String ifMatch) {
        return respond(handlers.update(new CategoryHandlers.UpdateCategory(
                categoryId,
                IfMatch.expectedVersion(ifMatch),
                request.getName(),
                request.getParentId(),
                Boolean.TRUE.equals(request.getMoveToRoot()),
                CatalogRequests.status(request.getStatus()))));
    }

    private ResponseEntity<Category> respond(CategoryView view) {
        return ResponseEntity.ok().eTag(Etags.of(view.version())).body(mapper.toDto(view));
    }
}
