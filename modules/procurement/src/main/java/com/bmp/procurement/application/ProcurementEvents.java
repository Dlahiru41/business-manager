package com.bmp.procurement.application;

import com.bmp.procurement.api.events.GoodsReceived;
import com.bmp.procurement.api.events.GoodsReceivedLine;
import com.bmp.procurement.api.events.SupplierInvoicePosted;
import com.bmp.procurement.domain.model.GoodsReceipt;
import com.bmp.procurement.domain.model.InvoiceMatch;
import com.bmp.procurement.domain.model.SupplierInvoice;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.event.EventStamp;
import com.bmp.shared.event.EventStampSource;
import com.bmp.shared.id.LocationId;
import com.bmp.shared.id.PackagingUnitId;
import com.bmp.shared.id.PartyId;
import com.bmp.shared.id.VariantId;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Publishes {@code GoodsReceived} (PRO-04) and {@code SupplierInvoicePosted} (PRO-05) from the saved documents. */
@Component
public class ProcurementEvents {

    private final ApplicationEventPublisher publisher;
    private final EventStampSource stamps;

    public ProcurementEvents(ApplicationEventPublisher publisher, EventStampSource stamps) {
        this.publisher = publisher;
        this.stamps = stamps;
    }

    public void goodsReceived(GoodsReceipt receipt) {
        EventStamp stamp = stamps.next();
        publisher.publishEvent(new GoodsReceived(
                stamp.eventId(),
                TenantContext.require(),
                stamp.occurredAt(),
                receipt.id(),
                receipt.version(),
                stamp.metadata(),
                receipt.id(),
                receipt.number(),
                PartyId.of(receipt.supplierId()),
                LocationId.of(receipt.locationId()),
                receipt.purchaseOrderId(),
                receipt.correctsReceiptId(),
                receipt.receivedAt(),
                receipt.currency(),
                receipt.total(),
                receipt.lines().stream()
                        .map(l -> new GoodsReceivedLine(
                                l.id(),
                                l.purchaseOrderLineId(),
                                VariantId.of(l.variantId()),
                                PackagingUnitId.of(l.packagingUnitId()),
                                l.qty(),
                                l.qtyBase(),
                                l.stockState(),
                                l.unitCost(),
                                l.unitCostBase(),
                                l.lineTotal(),
                                l.batchCode(),
                                l.expiresOn()))
                        .toList()));
    }

    public void invoicePosted(SupplierInvoice invoice) {
        EventStamp stamp = stamps.next();
        publisher.publishEvent(new SupplierInvoicePosted(
                stamp.eventId(),
                TenantContext.require(),
                stamp.occurredAt(),
                invoice.id(),
                invoice.version(),
                stamp.metadata(),
                invoice.id(),
                PartyId.of(invoice.supplierId()),
                invoice.supplierRef(),
                invoice.invoiceDate(),
                invoice.dueDate(),
                invoice.currency(),
                invoice.subtotal(),
                invoice.tax(),
                invoice.total(),
                invoice.receiptValue(),
                invoice.priceVariance(),
                invoice.receipts().stream().map(InvoiceMatch::receiptId).toList()));
    }
}
