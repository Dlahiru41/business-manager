package com.bmp.catalog.adapter.in.web;

import com.bmp.catalog.adapter.in.web.generated.api.UnitsApi;
import com.bmp.catalog.adapter.in.web.generated.model.CreateUnitRequest;
import com.bmp.catalog.adapter.in.web.generated.model.Dimension;
import com.bmp.catalog.adapter.in.web.generated.model.Unit;
import com.bmp.catalog.adapter.in.web.generated.model.UnitPage;
import com.bmp.catalog.adapter.in.web.generated.model.UnitPagePage;
import com.bmp.catalog.application.command.UnitHandlers;
import com.bmp.catalog.application.query.CatalogQueryService;
import com.bmp.catalog.application.query.UnitView;
import com.bmp.web.idempotency.Idempotent;
import com.bmp.web.pagination.PageQuery;
import com.bmp.web.pagination.PageResponse;
import java.net.URI;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/** Units of measure: the standard ones and the tenant's own (CAT-01). */
@RestController
class UnitsController implements UnitsApi {

    private final CatalogQueryService queries;
    private final UnitHandlers handlers;
    private final CatalogWebMapper mapper;

    UnitsController(CatalogQueryService queries, UnitHandlers handlers, CatalogWebMapper mapper) {
        this.queries = queries;
        this.handlers = handlers;
        this.mapper = mapper;
    }

    @Override
    @PreAuthorize("hasPermission(null, 'catalog:read')")
    public ResponseEntity<UnitPage> listUnits(
            @Nullable Dimension dimension, @Nullable Integer limit, @Nullable String cursor) {
        PageResponse<UnitView> page = queries.units(
                dimension != null ? com.bmp.catalog.domain.model.Dimension.valueOf(dimension.getValue()) : null,
                PageQuery.of(limit, cursor));
        return ResponseEntity.ok(new UnitPage()
                .items(page.items().stream().map(mapper::toDto).toList())
                .page(Pages.of(page)));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'catalog:write')")
    public ResponseEntity<Unit> createUnit(UUID idempotencyKey, CreateUnitRequest request) {
        UnitView created = handlers.create(new UnitHandlers.CreateUnit(
                request.getCode(),
                request.getName(),
                com.bmp.catalog.domain.model.Dimension.valueOf(
                        request.getDimension().getValue()),
                CatalogRequests.decimal(request.getToSiFactor())));
        return ResponseEntity.created(URI.create("/api/v1/units?code=" + created.code()))
                .body(mapper.toDto(created));
    }

    /** The page envelope of every catalog list. */
    static final class Pages {
        private Pages() {}

        static UnitPagePage of(PageResponse<?> page) {
            return new UnitPagePage()
                    .limit(page.page().limit())
                    .nextCursor(page.page().nextCursor())
                    .hasMore(page.page().hasMore());
        }
    }
}
