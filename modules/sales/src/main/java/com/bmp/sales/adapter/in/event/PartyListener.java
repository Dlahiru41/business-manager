package com.bmp.sales.adapter.in.event;

import com.bmp.party.api.events.PartyCreated;
import com.bmp.party.api.events.PartyUpdated;
import com.bmp.persistence.event.InboxGuard;
import com.bmp.sales.application.command.ReplicaHandlers;
import com.bmp.sales.domain.port.CustomerStore;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Replicates customers with their credit terms (SAL-07); no contact details travel. */
@Component
class PartyListener {

    static final String CONSUMER = "sales.PartyListener";

    private final InboxGuard inbox;
    private final ReplicaHandlers replicas;

    PartyListener(InboxGuard inbox, ReplicaHandlers replicas) {
        this.inbox = inbox;
        this.replicas = replicas;
    }

    @ApplicationModuleListener
    void on(PartyCreated e) {
        if (inbox.firstDelivery(CONSUMER, e.eventId())) {
            replicas.customer(
                    new CustomerStore.Customer(
                            e.partyId().value(),
                            e.type(),
                            e.displayName(),
                            e.customerGroupId(),
                            "ACTIVE".equals(e.status()),
                            e.creditPolicy().creditAllowed(),
                            e.creditPolicy().creditLimit()),
                    e.aggregateVersion());
        }
    }

    @ApplicationModuleListener
    void on(PartyUpdated e) {
        if (inbox.firstDelivery(CONSUMER, e.eventId())) {
            replicas.customer(
                    new CustomerStore.Customer(
                            e.partyId().value(),
                            e.type(),
                            e.displayName(),
                            e.customerGroupId(),
                            "ACTIVE".equals(e.status()),
                            e.creditPolicy().creditAllowed(),
                            e.creditPolicy().creditLimit()),
                    e.aggregateVersion());
        }
    }
}
