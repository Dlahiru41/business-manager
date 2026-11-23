package com.bmp.procurement.application;

import com.bmp.procurement.domain.error.ProcurementErrorCode;
import com.bmp.procurement.domain.model.GoodsReceipt;
import com.bmp.procurement.domain.model.PurchaseOrder;
import com.bmp.procurement.domain.model.SupplierInvoice;
import com.bmp.procurement.domain.port.GoodsReceiptRepository;
import com.bmp.procurement.domain.port.PurchaseOrderRepository;
import com.bmp.procurement.domain.port.SupplierInvoiceRepository;
import com.bmp.shared.error.NotFoundException;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Loads procurement documents of the current tenant, or answers 404 (also for other tenants' ids). */
@Component
public class ProcurementLoader {

    private final PurchaseOrderRepository orders;
    private final GoodsReceiptRepository receipts;
    private final SupplierInvoiceRepository invoices;

    public ProcurementLoader(
            PurchaseOrderRepository orders, GoodsReceiptRepository receipts, SupplierInvoiceRepository invoices) {
        this.orders = orders;
        this.receipts = receipts;
        this.invoices = invoices;
    }

    public PurchaseOrder order(UUID id) {
        PurchaseOrder order = orders.findById(id)
                .orElseThrow(() ->
                        new NotFoundException(ProcurementErrorCode.PURCHASE_ORDER_NOT_FOUND, Map.of("orderId", id)));
        order.lines();
        return order;
    }

    public GoodsReceipt receipt(UUID id) {
        GoodsReceipt receipt = receipts.findById(id)
                .orElseThrow(() ->
                        new NotFoundException(ProcurementErrorCode.GOODS_RECEIPT_NOT_FOUND, Map.of("receiptId", id)));
        receipt.lines();
        return receipt;
    }

    public SupplierInvoice invoice(UUID id) {
        SupplierInvoice invoice = invoices.findById(id)
                .orElseThrow(() -> new NotFoundException(
                        ProcurementErrorCode.SUPPLIER_INVOICE_NOT_FOUND, Map.of("invoiceId", id)));
        invoice.receipts();
        return invoice;
    }
}
