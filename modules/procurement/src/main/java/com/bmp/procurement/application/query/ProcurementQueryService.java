package com.bmp.procurement.application.query;

import com.bmp.procurement.application.ProcurementLoader;
import com.bmp.procurement.domain.model.GoodsReceipt;
import com.bmp.procurement.domain.model.InvoiceStatus;
import com.bmp.procurement.domain.model.OrderStatus;
import com.bmp.procurement.domain.model.PurchaseOrder;
import com.bmp.procurement.domain.model.ReceiptStatus;
import com.bmp.procurement.domain.model.SupplierInvoice;
import com.bmp.procurement.domain.port.GoodsReceiptRepository;
import com.bmp.procurement.domain.port.PurchaseOrderRepository;
import com.bmp.procurement.domain.port.SupplierInvoiceRepository;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.web.pagination.CursorCodec;
import com.bmp.web.pagination.PageQuery;
import com.bmp.web.pagination.PageResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Pages and reads of procurement documents, lines loaded inside the transaction. */
@Service
public class ProcurementQueryService {

    private final PurchaseOrderRepository orders;
    private final GoodsReceiptRepository receipts;
    private final SupplierInvoiceRepository invoices;
    private final ProcurementLoader loader;

    public ProcurementQueryService(
            PurchaseOrderRepository orders,
            GoodsReceiptRepository receipts,
            SupplierInvoiceRepository invoices,
            ProcurementLoader loader) {
        this.orders = orders;
        this.receipts = receipts;
        this.invoices = invoices;
        this.loader = loader;
    }

    @Transactional(readOnly = true)
    public PageResponse<PurchaseOrder> orders(
            @Nullable OrderStatus status, @Nullable UUID supplierId, PageQuery query) {
        List<PurchaseOrder> page = orders.findPage(status, supplierId, before(query), query.fetchSize());
        page.forEach(PurchaseOrder::lines);
        return PageResponse.of(page, query, o -> List.of(o.id().toString()));
    }

    @Transactional(readOnly = true)
    public PurchaseOrder order(UUID id) {
        return loader.order(id);
    }

    @Transactional(readOnly = true)
    public PageResponse<GoodsReceipt> receipts(
            @Nullable ReceiptStatus status,
            @Nullable UUID supplierId,
            @Nullable UUID purchaseOrderId,
            @Nullable Boolean invoiced,
            PageQuery query) {
        List<GoodsReceipt> page =
                receipts.findPage(status, supplierId, purchaseOrderId, invoiced, before(query), query.fetchSize());
        page.forEach(GoodsReceipt::lines);
        return PageResponse.of(page, query, r -> List.of(r.id().toString()));
    }

    @Transactional(readOnly = true)
    public GoodsReceipt receipt(UUID id) {
        return loader.receipt(id);
    }

    @Transactional(readOnly = true)
    public PageResponse<SupplierInvoice> invoices(
            @Nullable InvoiceStatus status, @Nullable UUID supplierId, PageQuery query) {
        List<SupplierInvoice> page = invoices.findPage(status, supplierId, before(query), query.fetchSize());
        page.forEach(SupplierInvoice::receipts);
        return PageResponse.of(page, query, i -> List.of(i.id().toString()));
    }

    @Transactional(readOnly = true)
    public SupplierInvoice invoice(UUID id) {
        return loader.invoice(id);
    }

    private static @Nullable UUID before(PageQuery query) {
        String cursor = query.cursor();
        if (cursor == null) {
            return null;
        }
        try {
            return UUID.fromString(CursorCodec.decode(cursor, 1).getFirst());
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleViolation(
                    PlatformErrorCode.INVALID_PARAMETER, "The cursor is invalid", Map.of("parameter", "cursor"));
        }
    }
}
