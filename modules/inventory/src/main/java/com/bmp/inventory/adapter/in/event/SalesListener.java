package com.bmp.inventory.adapter.in.event;

import com.bmp.inventory.application.command.SaleStockHandler;
import com.bmp.inventory.application.command.SaleStockHandler.RestockedLine;
import com.bmp.inventory.application.command.SaleStockHandler.Return;
import com.bmp.inventory.application.command.SaleStockHandler.Sale;
import com.bmp.inventory.application.command.SaleStockHandler.SoldLine;
import com.bmp.inventory.application.command.SaleStockHandler.Voided;
import com.bmp.persistence.event.InboxGuard;
import com.bmp.sales.api.events.SaleCompleted;
import com.bmp.sales.api.events.SaleReturned;
import com.bmp.sales.api.events.SaleVoided;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Books completed, returned and voided bills into stock (T03-04); the inbox makes redelivery harmless. */
@Component
class SalesListener {

    static final String CONSUMER = "inventory.SalesListener";

    private static final Logger log = LoggerFactory.getLogger(SalesListener.class);

    private final InboxGuard inbox;
    private final SaleStockHandler stock;

    SalesListener(InboxGuard inbox, SaleStockHandler stock) {
        this.inbox = inbox;
        this.stock = stock;
    }

    @ApplicationModuleListener
    void on(SaleCompleted event) {
        if (redelivered(event.eventId())) {
            return;
        }
        stock.sold(new Sale(
                event.eventId(),
                event.billId(),
                event.locationId().value(),
                event.completedAt(),
                event.currency(),
                event.lines().stream()
                        .map(l -> new SoldLine(l.lineId(), l.variantId().value(), l.stockState(), l.qtyBase()))
                        .toList()));
    }

    @ApplicationModuleListener
    void on(SaleReturned event) {
        if (redelivered(event.eventId())) {
            return;
        }
        stock.returned(new Return(
                event.eventId(),
                event.creditNoteId(),
                event.billId(),
                event.locationId().value(),
                event.returnedAt(),
                event.currency(),
                event.lines().stream()
                        .filter(l -> l.restock())
                        .map(l -> new RestockedLine(l.billLineId(), l.qtyBase()))
                        .toList()));
    }

    @ApplicationModuleListener
    void on(SaleVoided event) {
        if (redelivered(event.eventId())) {
            return;
        }
        stock.voided(new Voided(
                event.eventId(), event.billId(), event.locationId().value(), event.voidedAt(), event.currency()));
    }

    private boolean redelivered(java.util.UUID eventId) {
        if (inbox.firstDelivery(CONSUMER, eventId)) {
            return false;
        }
        log.debug("Skipping redelivered event eventId={}", eventId);
        return true;
    }
}
