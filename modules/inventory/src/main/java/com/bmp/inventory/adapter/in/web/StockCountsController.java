package com.bmp.inventory.adapter.in.web;

import com.bmp.inventory.adapter.in.web.generated.api.StockCountsApi;
import com.bmp.inventory.adapter.in.web.generated.model.CountLinesRequest;
import com.bmp.inventory.adapter.in.web.generated.model.CountStatus;
import com.bmp.inventory.adapter.in.web.generated.model.CreateStockCountRequest;
import com.bmp.inventory.adapter.in.web.generated.model.StockCount;
import com.bmp.inventory.adapter.in.web.generated.model.StockCountPage;
import com.bmp.inventory.application.InventorySettings;
import com.bmp.inventory.application.command.StockCountHandlers;
import com.bmp.inventory.application.query.InventoryQueryService;
import com.bmp.web.etag.Etags;
import com.bmp.web.etag.IfMatch;
import com.bmp.web.idempotency.Idempotent;
import com.bmp.web.pagination.PageQuery;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/** Stock counts (INV-12). */
@RestController
class StockCountsController implements StockCountsApi {

    private final StockCountHandlers handlers;
    private final InventoryQueryService queries;
    private final InventorySettings settings;
    private final InventoryWebMapper mapper;

    StockCountsController(
            StockCountHandlers handlers,
            InventoryQueryService queries,
            InventorySettings settings,
            InventoryWebMapper mapper) {
        this.handlers = handlers;
        this.queries = queries;
        this.settings = settings;
        this.mapper = mapper;
    }

    @Override
    @PreAuthorize("hasPermission(null, 'inventory:count')")
    public ResponseEntity<StockCountPage> listStockCounts(
            @Nullable CountStatus status, @Nullable UUID locationId, @Nullable Integer limit, @Nullable String cursor) {
        var page = queries.counts(
                status == null ? null : com.bmp.inventory.domain.model.CountStatus.valueOf(status.name()),
                locationId,
                PageQuery.of(limit, cursor));
        String currency = settings.tenant().currency();
        return ResponseEntity.ok(new StockCountPage()
                .items(page.items().stream().map(c -> mapper.count(c, currency)).toList())
                .page(mapper.page(page)));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'inventory:count')")
    public ResponseEntity<StockCount> createStockCount(UUID idempotencyKey, CreateStockCountRequest request) {
        var count = handlers.open(
                request.getLocationId(),
                request.getNote(),
                request.getVariantIds() == null ? List.of() : request.getVariantIds());
        return ResponseEntity.created(URI.create("/api/v1/stock/counts/" + count.id()))
                .eTag(Etags.of(count.version()))
                .body(mapper.count(count, settings.tenant().currency()));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'inventory:count')")
    public ResponseEntity<StockCount> getStockCount(UUID countId) {
        return ok(queries.count(countId));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'inventory:count')")
    public ResponseEntity<StockCount> recordCountLines(
            UUID countId, CountLinesRequest request, @Nullable String ifMatch) {
        return ok(handlers.record(
                countId,
                IfMatch.expectedVersion(ifMatch),
                request.getLines().stream()
                        .map(l -> new StockCountHandlers.Counted(
                                l.getVariantId(),
                                l.getStockState(),
                                l.getBatchId(),
                                new BigDecimal(l.getCountedQty()),
                                l.getVarianceReason()))
                        .toList()));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'inventory:count')")
    public ResponseEntity<StockCount> submitStockCount(UUID countId, UUID idempotencyKey) {
        return ok(handlers.submit(countId));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'inventory:count')")
    public ResponseEntity<StockCount> postStockCount(UUID countId, UUID idempotencyKey) {
        return ok(handlers.post(countId));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'inventory:count')")
    public ResponseEntity<StockCount> cancelStockCount(UUID countId, UUID idempotencyKey) {
        return ok(handlers.cancel(countId));
    }

    private ResponseEntity<StockCount> ok(com.bmp.inventory.domain.model.StockCount count) {
        return ResponseEntity.ok()
                .eTag(Etags.of(count.version()))
                .body(mapper.count(count, settings.tenant().currency()));
    }
}
