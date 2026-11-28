package com.bmp.finance.adapter.in.event;

import com.bmp.finance.application.command.PartyReplicaHandler;
import com.bmp.party.api.events.PartyCreated;
import com.bmp.party.api.events.PartyUpdated;
import com.bmp.persistence.event.InboxGuard;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Replicates parties' payment terms. Inbox-guarded: a redelivered event changes nothing (07 §4). */
@Component
class PartyTermsListener {

    static final String CONSUMER = "finance.PartyTermsListener";

    private static final Logger log = LoggerFactory.getLogger(PartyTermsListener.class);

    private final InboxGuard inbox;
    private final PartyReplicaHandler parties;

    PartyTermsListener(InboxGuard inbox, PartyReplicaHandler parties) {
        this.inbox = inbox;
        this.parties = parties;
    }

    @ApplicationModuleListener
    void on(PartyCreated event) {
        if (redelivered(event.eventId())) {
            return;
        }
        parties.replicate(
                event.partyId(), event.type(), event.status(), event.creditPolicy(), event.aggregateVersion());
    }

    @ApplicationModuleListener
    void on(PartyUpdated event) {
        if (redelivered(event.eventId())) {
            return;
        }
        parties.replicate(
                event.partyId(), event.type(), event.status(), event.creditPolicy(), event.aggregateVersion());
    }

    private boolean redelivered(UUID eventId) {
        if (inbox.firstDelivery(CONSUMER, eventId)) {
            return false;
        }
        log.debug("Skipping redelivered event eventId={}", eventId);
        return true;
    }
}
