package com.bmp.procurement.adapter.in.web;

import com.bmp.procurement.adapter.in.web.generated.api.GoodsReceiptsApi;
import com.bmp.procurement.adapter.in.web.generated.model.CreateGoodsReceiptRequest;
import com.bmp.procurement.adapter.in.web.generated.model.GoodsReceipt;
import com.bmp.procurement.adapter.in.web.generated.model.GoodsReceiptLineInput;
import com.bmp.procurement.adapter.in.web.generated.model.GoodsReceiptPage;
import com.bmp.procurement.adapter.in.web.generated.model.GoodsReceiptStatus;
import com.bmp.procurement.adapter.in.web.generated.model.UpdateGoodsReceiptRequest;
import com.bmp.procurement.application.command.GoodsReceiptHandlers;
import com.bmp.procurement.application.query.ProcurementQueryService;
import com.bmp.procurement.domain.model.ReceiptStatus;
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

/** Goods receipts (PRO-02..PRO-04). */
@RestController
class GoodsReceiptsController implements GoodsReceiptsApi {

    private final GoodsReceiptHandlers handlers;
    private final ProcurementQueryService queries;
    private final ProcurementWebMapper mapper;

    GoodsReceiptsController(
            GoodsReceiptHandlers handlers, ProcurementQueryService queries, ProcurementWebMapper mapper) {
        this.handlers = handlers;
        this.queries = queries;
        this.mapper = mapper;
    }

    @Override
    @PreAuthorize("hasPermission(null, 'procurement:read')")
    public ResponseEntity<GoodsReceiptPage> listGoodsReceipts(
            @Nullable GoodsReceiptStatus status,
            @Nullable UUID supplierId,
            @Nullable UUID purchaseOrderId,
            @Nullable Boolean invoiced,
            @Nullable Integer limit,
            @Nullable String cursor) {
        var page = queries.receipts(
                status == null ? null : ReceiptStatus.valueOf(status.name()),
                supplierId,
                purchaseOrderId,
                invoiced,
                PageQuery.of(limit, cursor));
        return ResponseEntity.ok(new GoodsReceiptPage()
                .items(page.items().stream().map(mapper::receipt).toList())
                .page(mapper.page(page)));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'procurement:receive')")
    public ResponseEntity<GoodsReceipt> createGoodsReceipt(UUID idempotencyKey, CreateGoodsReceiptRequest request) {
        var receipt = handlers.create(new GoodsReceiptHandlers.CreateReceipt(
                request.getSupplierId(),
                request.getLocationId(),
                request.getPurchaseOrderId(),
                request.getCorrectsReceiptId(),
                ProcurementWebMapper.instant(request.getReceivedAt()),
                request.getNotes(),
                lines(request.getLines())));
        return ResponseEntity.created(URI.create("/api/v1/goods-receipts/" + receipt.id()))
                .eTag(Etags.of(receipt.version()))
                .body(mapper.receipt(receipt));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'procurement:read')")
    public ResponseEntity<GoodsReceipt> getGoodsReceipt(UUID receiptId) {
        return ok(queries.receipt(receiptId));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'procurement:receive')")
    public ResponseEntity<GoodsReceipt> updateGoodsReceipt(
            UUID receiptId, UpdateGoodsReceiptRequest request, @Nullable String ifMatch) {
        return ok(handlers.update(
                receiptId,
                IfMatch.expectedVersion(ifMatch),
                ProcurementWebMapper.instant(request.getReceivedAt()),
                request.getNotes(),
                request.getLines() == null ? null : lines(request.getLines())));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'procurement:receive')")
    public ResponseEntity<GoodsReceipt> postGoodsReceipt(UUID receiptId, UUID idempotencyKey) {
        return ok(handlers.post(receiptId));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'procurement:receive')")
    public ResponseEntity<GoodsReceipt> cancelGoodsReceipt(UUID receiptId, UUID idempotencyKey) {
        return ok(handlers.cancel(receiptId));
    }

    private ResponseEntity<GoodsReceipt> ok(com.bmp.procurement.domain.model.GoodsReceipt receipt) {
        return ResponseEntity.ok().eTag(Etags.of(receipt.version())).body(mapper.receipt(receipt));
    }

    private static List<GoodsReceiptHandlers.LineInput> lines(List<GoodsReceiptLineInput> lines) {
        return lines.stream()
                .map(l -> new GoodsReceiptHandlers.LineInput(
                        l.getPurchaseOrderLineId(),
                        l.getVariantId(),
                        l.getPackagingUnitId(),
                        new BigDecimal(l.getQty()),
                        new BigDecimal(l.getUnitCost()),
                        l.getBatchCode(),
                        l.getExpiresOn()))
                .toList();
    }
}
