package com.bmp.finance.adapter.in.event;

import com.bmp.finance.application.command.SeedChartHandler;
import com.bmp.persistence.event.InboxGuard;
import com.bmp.tenant.api.events.TenantCreated;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Seeds the chart of accounts of a new tenant (FIN-01). Inbox-guarded: a redelivered event changes nothing (07 §4). */
@Component
class TenantListener {

    static final String CONSUMER = "finance.TenantListener";

    private static final Logger log = LoggerFactory.getLogger(TenantListener.class);

    private final InboxGuard inbox;
    private final SeedChartHandler chart;

    TenantListener(InboxGuard inbox, SeedChartHandler chart) {
        this.inbox = inbox;
        this.chart = chart;
    }

    @ApplicationModuleListener
    void on(TenantCreated event) {
        if (redelivered(event.eventId())) {
            return;
        }
        chart.seed();
    }

    private boolean redelivered(UUID eventId) {
        if (inbox.firstDelivery(CONSUMER, eventId)) {
            return false;
        }
        log.debug("Skipping redelivered event eventId={}", eventId);
        return true;
    }
}
