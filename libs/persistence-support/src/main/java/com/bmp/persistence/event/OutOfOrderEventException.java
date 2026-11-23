package com.bmp.persistence.event;

import java.io.Serial;
import java.util.UUID;

/**
 * Thrown by a listener whose event arrived before something it depends on (07 §5 "Ordering"): unlike a
 * {@link com.bmp.shared.error.DomainException} it is not parked, so the resubmission job delivers the event again later.
 * The listener's transaction, its inbox row included, rolls back.
 */
public final class OutOfOrderEventException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public OutOfOrderEventException(UUID eventId, String missing) {
        super("Event " + eventId + " arrived before " + missing);
    }
}
