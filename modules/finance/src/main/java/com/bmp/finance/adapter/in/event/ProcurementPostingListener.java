package com.bmp.finance.adapter.in.event;

import com.bmp.finance.application.command.PurchasePostingHandler;
import com.bmp.persistence.event.InboxGuard;
import com.bmp.procurement.api.events.GoodsReceived;
import com.bmp.procurement.api.events.SupplierInvoicePosted;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Posts goods receipts and supplier invoices. Inbox-guarded: a redelivered event changes nothing (07 §4). */
@Component
class ProcurementPostingListener {

    static final String CONSUMER = "finance.ProcurementPostingListener";

    private static final Logger log = LoggerFactory.getLogger(ProcurementPostingListener.class);

    private final InboxGuard inbox;
    private final PurchasePostingHandler purchases;

    ProcurementPostingListener(InboxGuard inbox, PurchasePostingHandler purchases) {
        this.inbox = inbox;
        this.purchases = purchases;
    }

    @ApplicationModuleListener
    void on(GoodsReceived event) {
        if (redelivered(event.eventId())) {
            return;
        }
        purchases.goodsReceived(event);
    }

    @ApplicationModuleListener
    void on(SupplierInvoicePosted event) {
        if (redelivered(event.eventId())) {
            return;
        }
        purchases.invoicePosted(event);
    }

    private boolean redelivered(UUID eventId) {
        if (inbox.firstDelivery(CONSUMER, eventId)) {
            return false;
        }
        log.debug("Skipping redelivered event eventId={}", eventId);
        return true;
    }
}
