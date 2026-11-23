package com.bmp.inventory.adapter.in.web;

import com.bmp.inventory.adapter.in.web.generated.api.StockApi;
import com.bmp.inventory.adapter.in.web.generated.model.MovementType;
import com.bmp.inventory.adapter.in.web.generated.model.RebuildResult;
import com.bmp.inventory.adapter.in.web.generated.model.StockBalancePage;
import com.bmp.inventory.adapter.in.web.generated.model.StockMovementPage;
import com.bmp.inventory.adapter.in.web.generated.model.Valuation;
import com.bmp.inventory.adapter.in.web.generated.model.ValuationGroup;
import com.bmp.inventory.application.command.RebuildBalancesHandler;
import com.bmp.inventory.application.query.StockQueryService;
import com.bmp.inventory.application.query.StockReadModel.BalanceFilter;
import com.bmp.inventory.application.query.StockReadModel.Grouping;
import com.bmp.inventory.application.query.StockReadModel.MovementFilter;
import com.bmp.web.idempotency.Idempotent;
import com.bmp.web.pagination.PageQuery;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/** Balances, the ledger, valuation and the balance rebuild (T02-07, INV-16). */
@RestController
class StockController implements StockApi {

    private final StockQueryService queries;
    private final RebuildBalancesHandler rebuild;
    private final InventoryWebMapper mapper;

    StockController(StockQueryService queries, RebuildBalancesHandler rebuild, InventoryWebMapper mapper) {
        this.queries = queries;
        this.rebuild = rebuild;
        this.mapper = mapper;
    }

    @Override
    @PreAuthorize("hasPermission(null, 'inventory:read')")
    public ResponseEntity<StockBalancePage> listStockBalances(
            @Nullable UUID variantId,
            @Nullable UUID locationId,
            @Nullable String state,
            @Nullable Boolean belowReorder,
            @Nullable Boolean negative,
            Integer limit,
            @Nullable String cursor) {
        var page = queries.balances(
                new BalanceFilter(
                        variantId, locationId, state, Boolean.TRUE.equals(belowReorder), Boolean.TRUE.equals(negative)),
                PageQuery.of(limit, cursor));
        String currency = queries.currency();
        return ResponseEntity.ok(new StockBalancePage()
                .items(page.items().stream()
                        .map(b -> mapper.balance(b, currency))
                        .toList())
                .page(mapper.page(page)));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'inventory:read')")
    public ResponseEntity<StockMovementPage> listStockMovements(
            @Nullable UUID variantId,
            @Nullable UUID locationId,
            @Nullable MovementType type,
            @Nullable OffsetDateTime from,
            @Nullable OffsetDateTime to,
            Integer limit,
            @Nullable String cursor) {
        var page = queries.movements(
                new MovementFilter(
                        variantId,
                        locationId,
                        type == null ? null : type.getValue(),
                        from == null ? null : from.toInstant(),
                        to == null ? null : to.toInstant()),
                PageQuery.of(limit, cursor));
        return ResponseEntity.ok(new StockMovementPage()
                .items(page.items().stream().map(mapper::movement).toList())
                .page(mapper.page(page)));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'inventory:read')")
    public ResponseEntity<Valuation> getValuation(
            @Nullable OffsetDateTime asOf, @Nullable ValuationGroup groupBy, @Nullable UUID locationId) {
        Grouping grouping = groupBy == null ? Grouping.VARIANT : Grouping.valueOf(groupBy.name());
        return ResponseEntity.ok(
                mapper.valuation(queries.valuation(asOf == null ? null : asOf.toInstant(), grouping, locationId)));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'inventory:admin')")
    public ResponseEntity<RebuildResult> rebuildStockBalances(UUID idempotencyKey) {
        var result = rebuild.rebuild();
        return ResponseEntity.ok(new RebuildResult()
                .balances(result.balances())
                .differences(result.differences())
                .rebuiltAt(mapper.time(result.rebuiltAt())));
    }
}
