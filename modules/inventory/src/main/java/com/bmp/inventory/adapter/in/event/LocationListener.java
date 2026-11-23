package com.bmp.inventory.adapter.in.event;

import com.bmp.inventory.application.command.ReplicateCatalogHandler;
import com.bmp.inventory.domain.model.LocationRef;
import com.bmp.persistence.event.InboxGuard;
import com.bmp.tenant.api.events.LocationCreated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Replicates the tenant's locations (T02-03); the inbox makes redelivery harmless. */
@Component
class LocationListener {

    static final String CONSUMER = "inventory.LocationListener";

    private static final Logger log = LoggerFactory.getLogger(LocationListener.class);

    private final InboxGuard inbox;
    private final ReplicateCatalogHandler replicas;

    LocationListener(InboxGuard inbox, ReplicateCatalogHandler replicas) {
        this.inbox = inbox;
        this.replicas = replicas;
    }

    @ApplicationModuleListener
    void on(LocationCreated event) {
        if (!inbox.firstDelivery(CONSUMER, event.eventId())) {
            log.debug("Skipping redelivered event eventId={}", event.eventId());
            return;
        }
        replicas.location(new LocationRef(event.locationId().value(), event.name(), event.type(), true));
    }
}
