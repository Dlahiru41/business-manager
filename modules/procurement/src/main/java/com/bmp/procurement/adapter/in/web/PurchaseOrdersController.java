package com.bmp.procurement.adapter.in.web;

import com.bmp.procurement.adapter.in.web.generated.api.PurchaseOrdersApi;
import com.bmp.procurement.adapter.in.web.generated.model.CreatePurchaseOrderRequest;
import com.bmp.procurement.adapter.in.web.generated.model.FromReorderListRequest;
import com.bmp.procurement.adapter.in.web.generated.model.FromReorderListResult;
import com.bmp.procurement.adapter.in.web.generated.model.PurchaseOrder;
import com.bmp.procurement.adapter.in.web.generated.model.PurchaseOrderLineInput;
import com.bmp.procurement.adapter.in.web.generated.model.PurchaseOrderPage;
import com.bmp.procurement.adapter.in.web.generated.model.PurchaseOrderStatus;
import com.bmp.procurement.adapter.in.web.generated.model.UpdatePurchaseOrderRequest;
import com.bmp.procurement.application.command.PurchaseOrderHandlers;
import com.bmp.procurement.application.query.ProcurementQueryService;
import com.bmp.procurement.domain.model.OrderStatus;
import com.bmp.web.etag.Etags;
import com.bmp.web.etag.IfMatch;
import com.bmp.web.idempotency.Idempotent;
import com.bmp.web.pagination.PageQuery;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/** Purchase orders (PRO-01, PRO-06). */
@RestController
class PurchaseOrdersController implements PurchaseOrdersApi {

    private final PurchaseOrderHandlers handlers;
    private final ProcurementQueryService queries;
    private final ProcurementWebMapper mapper;

    PurchaseOrdersController(
            PurchaseOrderHandlers handlers, ProcurementQueryService queries, ProcurementWebMapper mapper) {
        this.handlers = handlers;
        this.queries = queries;
        this.mapper = mapper;
    }

    @Override
    @PreAuthorize("hasPermission(null, 'procurement:read')")
    public ResponseEntity<PurchaseOrderPage> listPurchaseOrders(
            @Nullable PurchaseOrderStatus status,
            @Nullable UUID supplierId,
            @Nullable Integer limit,
            @Nullable String cursor) {
        var page = queries.orders(
                status == null ? null : OrderStatus.valueOf(status.name()), supplierId, PageQuery.of(limit, cursor));
        return ResponseEntity.ok(new PurchaseOrderPage()
                .items(page.items().stream().map(mapper::order).toList())
                .page(mapper.page(page)));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'procurement:order')")
    public ResponseEntity<PurchaseOrder> createPurchaseOrder(UUID idempotencyKey, CreatePurchaseOrderRequest request) {
        var order = handlers.create(
                request.getSupplierId(),
                request.getLocationId(),
                request.getExpectedOn(),
                request.getNotes(),
                lines(request.getLines()));
        return ResponseEntity.created(URI.create("/api/v1/purchase-orders/" + order.id()))
                .eTag(Etags.of(order.version()))
                .body(mapper.order(order));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'procurement:read')")
    public ResponseEntity<PurchaseOrder> getPurchaseOrder(UUID orderId) {
        return ok(queries.order(orderId));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'procurement:order')")
    public ResponseEntity<PurchaseOrder> updatePurchaseOrder(
            UUID orderId, UpdatePurchaseOrderRequest request, @Nullable String ifMatch) {
        return ok(handlers.update(
                orderId,
                IfMatch.expectedVersion(ifMatch),
                request.getExpectedOn(),
                request.getNotes(),
                request.getLines() == null ? null : lines(request.getLines())));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'procurement:order')")
    public ResponseEntity<PurchaseOrder> sendPurchaseOrder(UUID orderId, UUID idempotencyKey) {
        return ok(handlers.send(orderId));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'procurement:order')")
    public ResponseEntity<PurchaseOrder> cancelPurchaseOrder(UUID orderId, UUID idempotencyKey) {
        return ok(handlers.cancel(orderId));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'procurement:order')")
    public ResponseEntity<FromReorderListResult> createPurchaseOrdersFromReorderList(
            UUID idempotencyKey, FromReorderListRequest request) {
        var result = handlers.fromReorderList(request.getLocationId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new FromReorderListResult()
                        .orders(result.orders().stream().map(mapper::order).toList())
                        .skippedVariantIds(result.skippedVariantIds()));
    }

    private ResponseEntity<PurchaseOrder> ok(com.bmp.procurement.domain.model.PurchaseOrder order) {
        return ResponseEntity.ok().eTag(Etags.of(order.version())).body(mapper.order(order));
    }

    private static List<PurchaseOrderHandlers.LineInput> lines(List<PurchaseOrderLineInput> lines) {
        return lines.stream()
                .map(l -> new PurchaseOrderHandlers.LineInput(
                        l.getVariantId(),
                        l.getPackagingUnitId(),
                        new BigDecimal(l.getQty()),
                        new BigDecimal(l.getUnitCost())))
                .toList();
    }
}
