package com.bmp.reporting.domain.port;

import com.bmp.reporting.domain.model.Fact;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Reporting's own record of the events it applied (ADR-033 §1): the source of every rebuild. */
public interface EventLog {

    /** Appends the fact of an event; false when the event is already there. */
    boolean append(UUID eventId, Instant occurredAt, Fact fact);

    /** A logged fact in recorded order. */
    record Entry(UUID eventId, Fact fact) {}

    /** The tenant's facts in the order they were recorded, from {@code offset}. */
    List<Entry> read(int offset, int limit);

    /** When the last event was recorded (REP-03). */
    Optional<Instant> lastRecordedAt();
}
