package com.bmp.audit.adapter.in.event;

import com.bmp.audit.api.AuditEntry;
import com.bmp.audit.api.AuditEntry.AuditSource;
import com.bmp.audit.api.AuditWriter;
import com.bmp.audit.domain.model.AuditRecord;
import com.bmp.persistence.event.InboxGuard;
import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.event.DomainEvents;
import com.bmp.shared.event.EventMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records every domain event of every module, generically: type, aggregate, actor, request and the SHA-256 of the
 * payload (the payload itself stays with its owner). Runs after the publishing transaction commits, in the event's
 * tenant; the inbox makes redelivery harmless.
 */
@Component
class DomainEventAuditor {

    static final String CONSUMER = "audit.DomainEventAuditor";

    private static final Logger log = LoggerFactory.getLogger(DomainEventAuditor.class);

    private final AuditWriter writer;
    private final InboxGuard inbox;
    private final JsonMapper json;

    DomainEventAuditor(AuditWriter writer, InboxGuard inbox, JsonMapper json) {
        this.writer = writer;
        this.inbox = inbox;
        this.json = json;
    }

    @ApplicationModuleListener
    void on(DomainEvent event) {
        if (!inbox.firstDelivery(CONSUMER, event.eventId())) {
            log.debug("Skipping redelivered event eventId={}", event.eventId());
            return;
        }
        String type = DomainEvents.typeName(event.getClass());
        EventMetadata metadata = event.metadata();
        writer.record(new AuditEntry(
                event.occurredAt(),
                AuditSource.EVENT,
                type,
                moduleOf(type),
                event.aggregateId().toString(),
                null,
                metadata.actorId(),
                metadata.deviceId(),
                false,
                null,
                AuditRecord.sha256(json.writeValueAsString(event)),
                null,
                null,
                metadata.requestId(),
                event.eventId()));
    }

    /** {@code bmp.<module>.<name>.v<n>} to {@code <module>}. */
    static String moduleOf(String type) {
        int start = type.indexOf('.') + 1;
        return type.substring(start, type.indexOf('.', start));
    }
}
