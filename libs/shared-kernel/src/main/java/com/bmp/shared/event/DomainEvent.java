package com.bmp.shared.event;

import com.bmp.shared.id.TenantId;
import java.time.Instant;
import java.util.UUID;

/**
 * A fact another module may react to. Events are immutable records in the publishing module's {@code api.events}
 * package, named in the past tense, carrying all data consumers need (but no personal data beyond ids). Published
 * inside the handler's transaction; delivered after commit through the Modulith event publication registry.
 */
public interface DomainEvent {

    /** UUIDv7, generated when the event is created; consumers de-duplicate on it (inbox). */
    UUID eventId();

    /** The tenant the event belongs to; listeners run with this tenant bound. */
    TenantId tenantId();

    Instant occurredAt();

    /** The id of the aggregate that changed. */
    UUID aggregateId();

    /** The aggregate version after the change, for ordering and de-duplication. */
    long aggregateVersion();

    /** Who caused the event and the request/trace it belongs to. */
    EventMetadata metadata();
}
