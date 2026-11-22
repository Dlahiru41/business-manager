package com.bmp.pricing.adapter.in.event;

import com.bmp.persistence.event.InboxGuard;
import com.bmp.pricing.application.command.SeedPriceListsHandler;
import com.bmp.tenant.api.events.TenantCreated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Rule PRC-01: seeds the Retail and Wholesale lists of a new tenant; the inbox makes redelivery harmless. */
@Component
class PriceListSeedingListener {

    static final String CONSUMER = "pricing.PriceListSeedingListener";

    private static final Logger log = LoggerFactory.getLogger(PriceListSeedingListener.class);

    private final InboxGuard inbox;
    private final SeedPriceListsHandler seeder;

    PriceListSeedingListener(InboxGuard inbox, SeedPriceListsHandler seeder) {
        this.inbox = inbox;
        this.seeder = seeder;
    }

    @ApplicationModuleListener
    void on(TenantCreated event) {
        if (!inbox.firstDelivery(CONSUMER, event.eventId())) {
            log.debug("Skipping redelivered event eventId={}", event.eventId());
            return;
        }
        seeder.seed(event.currency());
    }
}
