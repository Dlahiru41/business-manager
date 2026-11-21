package com.bmp.shared.event;

import com.bmp.shared.id.DeviceId;
import com.bmp.shared.id.UserId;
import org.jspecify.annotations.Nullable;

/**
 * Context travelling with an event so listeners can restore actor, request and trace (07-events-and-messaging.md §4).
 *
 * @param actorId the user who caused the event, if any
 * @param deviceId the device that caused the event, if any
 * @param requestId the HTTP request id, if the event was caused by a request
 * @param traceparent the W3C {@code traceparent} of the publishing span, if tracing was active
 */
public record EventMetadata(
        @Nullable UserId actorId,
        @Nullable DeviceId deviceId,
        @Nullable String requestId,
        @Nullable String traceparent) {

    private static final EventMetadata EMPTY = new EventMetadata(null, null, null, null);

    public static EventMetadata empty() {
        return EMPTY;
    }
}
