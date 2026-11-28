package com.bmp.finance.adapter.in.event;

import com.bmp.finance.application.PeriodCloseRequested;
import com.bmp.finance.application.command.PeriodCloseHandlers;
import com.bmp.persistence.event.InboxGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Runs an accepted period close (FIN-06) from the event publication registry; inbox-guarded. */
@Component
class PeriodCloseListener {

    static final String CONSUMER = "finance.PeriodCloseListener";

    private static final Logger log = LoggerFactory.getLogger(PeriodCloseListener.class);

    private final InboxGuard inbox;
    private final PeriodCloseHandlers close;

    PeriodCloseListener(InboxGuard inbox, PeriodCloseHandlers close) {
        this.inbox = inbox;
        this.close = close;
    }

    @ApplicationModuleListener
    void on(PeriodCloseRequested event) {
        if (!inbox.firstDelivery(CONSUMER, event.eventId())) {
            log.debug("Skipping redelivered event eventId={}", event.eventId());
            return;
        }
        close.run(event);
    }
}
