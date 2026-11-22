package com.bmp.audit.api;

import com.bmp.shared.id.DeviceId;
import com.bmp.shared.id.UserId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What happened, to be appended to the current tenant's audit chain. Ids only: no names, no payloads (a payload is
 * represented by its hash).
 *
 * @param action {@code METHOD /path/template} for API writes, the event type for events, a verb for system entries
 * @param resourceType the API collection (e.g. {@code users}) or, for events, the publishing module
 * @param outcome the HTTP status of an API write
 * @param platformAdmin a platform administrator acted for the tenant ({@code X-Tenant-Id})
 * @param beforeHash SHA-256 of the state before the change, when known
 * @param afterHash SHA-256 of the state or payload after the change, when known
 * @param eventId the domain event, for event records
 */
public record AuditEntry(
        Instant occurredAt,
        AuditSource source,
        String action,
        @Nullable String resourceType,
        @Nullable String resourceId,
        @Nullable Integer outcome,
        @Nullable UserId actorId,
        @Nullable DeviceId deviceId,
        boolean platformAdmin,
        @Nullable String beforeHash,
        @Nullable String afterHash,
        @Nullable String ip,
        @Nullable String userAgent,
        @Nullable String requestId,
        @Nullable UUID eventId) {

    public AuditEntry {
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(action, "action");
    }

    /** Where an audit entry comes from. */
    public enum AuditSource {
        /** A write through the REST API. */
        API,
        /** A domain event. */
        EVENT,
        /** Recorded explicitly by a module (jobs, permission-sensitive reads). */
        SYSTEM
    }
}
