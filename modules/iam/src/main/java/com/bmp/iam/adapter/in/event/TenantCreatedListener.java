package com.bmp.iam.adapter.in.event;

import com.bmp.iam.application.command.SeedSystemRolesHandler;
import com.bmp.persistence.event.InboxGuard;
import com.bmp.tenant.api.events.TenantCreated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Seeds the system roles of a new tenant. Runs after the tenant transaction commits, in its own transaction bound to
 * the event's tenant; the inbox makes redelivery harmless.
 */
@Component
class TenantCreatedListener {

    static final String CONSUMER = "iam.TenantCreatedListener";

    private static final Logger log = LoggerFactory.getLogger(TenantCreatedListener.class);

    private final InboxGuard inbox;
    private final SeedSystemRolesHandler seeder;

    TenantCreatedListener(InboxGuard inbox, SeedSystemRolesHandler seeder) {
        this.inbox = inbox;
        this.seeder = seeder;
    }

    @ApplicationModuleListener
    void on(TenantCreated event) {
        if (!inbox.firstDelivery(CONSUMER, event.eventId())) {
            log.debug("Skipping redelivered event eventId={}", event.eventId());
            return;
        }
        seeder.seed();
    }
}
