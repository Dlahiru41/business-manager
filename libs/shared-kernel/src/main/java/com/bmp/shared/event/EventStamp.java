package com.bmp.shared.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The envelope values of one event about to be recorded: a fresh event id, the time and the publishing context.
 * Aggregates receive stamps from an {@link EventStampSource} so domain code stays free of clocks and id generators.
 *
 * @param eventId UUIDv7 of the event
 * @param occurredAt when it happened
 * @param metadata actor, request and trace of the publisher
 */
public record EventStamp(UUID eventId, Instant occurredAt, EventMetadata metadata) {

    public EventStamp {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(metadata, "metadata");
    }
}
