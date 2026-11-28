package com.bmp.finance.adapter.in.event;

import com.bmp.finance.application.command.StockPostingHandler;
import com.bmp.inventory.api.events.StockAdjusted;
import com.bmp.inventory.api.events.StockCountPosted;
import com.bmp.inventory.api.events.StockIssued;
import com.bmp.inventory.api.events.StockReceived;
import com.bmp.inventory.api.events.StockTransformed;
import com.bmp.persistence.event.InboxGuard;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Posts the stock value of inventory's movements. Inbox-guarded: a redelivered event changes nothing (07 §4). */
@Component
class InventoryPostingListener {

    static final String CONSUMER = "finance.InventoryPostingListener";

    private static final Logger log = LoggerFactory.getLogger(InventoryPostingListener.class);

    private final InboxGuard inbox;
    private final StockPostingHandler stock;

    InventoryPostingListener(InboxGuard inbox, StockPostingHandler stock) {
        this.inbox = inbox;
        this.stock = stock;
    }

    @ApplicationModuleListener
    void on(StockIssued event) {
        if (redelivered(event.eventId())) {
            return;
        }
        stock.issued(event);
    }

    @ApplicationModuleListener
    void on(StockReceived event) {
        if (redelivered(event.eventId())) {
            return;
        }
        stock.received(event);
    }

    @ApplicationModuleListener
    void on(StockAdjusted event) {
        if (redelivered(event.eventId())) {
            return;
        }
        stock.adjusted(event);
    }

    @ApplicationModuleListener
    void on(StockCountPosted event) {
        if (redelivered(event.eventId())) {
            return;
        }
        stock.counted(event);
    }

    @ApplicationModuleListener
    void on(StockTransformed event) {
        if (redelivered(event.eventId())) {
            return;
        }
        stock.transformed(event);
    }

    private boolean redelivered(UUID eventId) {
        if (inbox.firstDelivery(CONSUMER, eventId)) {
            return false;
        }
        log.debug("Skipping redelivered event eventId={}", eventId);
        return true;
    }
}
