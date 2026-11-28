package com.bmp.finance.adapter.in.event;

import com.bmp.finance.application.command.SalesPostingHandler;
import com.bmp.persistence.event.InboxGuard;
import com.bmp.sales.api.events.SaleCompleted;
import com.bmp.sales.api.events.SaleReturned;
import com.bmp.sales.api.events.SaleVoided;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Posts bills, returns and voids. Inbox-guarded: a redelivered event changes nothing (07 §4). */
@Component
class SalesPostingListener {

    static final String CONSUMER = "finance.SalesPostingListener";

    private static final Logger log = LoggerFactory.getLogger(SalesPostingListener.class);

    private final InboxGuard inbox;
    private final SalesPostingHandler sales;

    SalesPostingListener(InboxGuard inbox, SalesPostingHandler sales) {
        this.inbox = inbox;
        this.sales = sales;
    }

    @ApplicationModuleListener
    void on(SaleCompleted event) {
        if (redelivered(event.eventId())) {
            return;
        }
        sales.completed(event);
    }

    @ApplicationModuleListener
    void on(SaleReturned event) {
        if (redelivered(event.eventId())) {
            return;
        }
        sales.returned(event);
    }

    @ApplicationModuleListener
    void on(SaleVoided event) {
        if (redelivered(event.eventId())) {
            return;
        }
        sales.voided(event);
    }

    private boolean redelivered(UUID eventId) {
        if (inbox.firstDelivery(CONSUMER, eventId)) {
            return false;
        }
        log.debug("Skipping redelivered event eventId={}", eventId);
        return true;
    }
}
