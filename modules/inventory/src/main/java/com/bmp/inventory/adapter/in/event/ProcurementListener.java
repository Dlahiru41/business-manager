package com.bmp.inventory.adapter.in.event;

import com.bmp.inventory.application.command.GoodsReceiptStockHandler;
import com.bmp.inventory.application.command.GoodsReceiptStockHandler.Receipt;
import com.bmp.inventory.application.command.GoodsReceiptStockHandler.ReceivedLine;
import com.bmp.persistence.event.InboxGuard;
import com.bmp.procurement.api.events.GoodsReceived;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Books posted goods receipts into stock (T02-06); the inbox makes redelivery harmless. */
@Component
class ProcurementListener {

    static final String CONSUMER = "inventory.ProcurementListener";

    private static final Logger log = LoggerFactory.getLogger(ProcurementListener.class);

    private final InboxGuard inbox;
    private final GoodsReceiptStockHandler receipts;

    ProcurementListener(InboxGuard inbox, GoodsReceiptStockHandler receipts) {
        this.inbox = inbox;
        this.receipts = receipts;
    }

    @ApplicationModuleListener
    void on(GoodsReceived event) {
        if (!inbox.firstDelivery(CONSUMER, event.eventId())) {
            log.debug("Skipping redelivered event eventId={}", event.eventId());
            return;
        }
        receipts.receive(new Receipt(
                event.receiptId(),
                event.supplierId().value(),
                event.locationId().value(),
                event.receivedAt(),
                event.currency(),
                event.lines().stream()
                        .map(l -> new ReceivedLine(
                                l.lineId(),
                                l.variantId().value(),
                                l.stockState(),
                                l.qtyBase(),
                                l.lineTotal(),
                                l.batchCode(),
                                l.expiresOn()))
                        .toList()));
    }
}
