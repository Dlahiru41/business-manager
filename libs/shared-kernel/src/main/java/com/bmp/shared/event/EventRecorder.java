package com.bmp.shared.event;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects the events an aggregate raises during a use case. The application handler drains and publishes them right
 * after saving the aggregate, inside the same transaction (ADR-013). Aggregates keep it in a {@code transient} field so
 * it is never persisted.
 */
public final class EventRecorder {

    private final List<DomainEvent> pending = new ArrayList<>();

    public void record(DomainEvent event) {
        pending.add(event);
    }

    /** Returns the recorded events in order and clears the recorder. */
    public List<DomainEvent> drain() {
        List<DomainEvent> drained = List.copyOf(pending);
        pending.clear();
        return drained;
    }

    public boolean isEmpty() {
        return pending.isEmpty();
    }
}
