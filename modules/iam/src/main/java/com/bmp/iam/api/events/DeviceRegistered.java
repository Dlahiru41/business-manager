package com.bmp.iam.api.events;

import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.DeviceId;
import com.bmp.shared.id.LocationId;
import com.bmp.shared.id.RegisterId;
import com.bmp.shared.id.TenantId;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A till was registered and received its credential ({@code bmp.iam.device-registered.v1}); sync and sales prepare
 * for it. The credential itself is never part of an event.
 *
 * @param deviceId the new device
 * @param registerId the billing counter it serves, if already known
 * @param locationId where it is installed
 */
public record DeviceRegistered(
        UUID eventId,
        TenantId tenantId,
        Instant occurredAt,
        UUID aggregateId,
        long aggregateVersion,
        EventMetadata metadata,
        DeviceId deviceId,
        @Nullable RegisterId registerId,
        LocationId locationId)
        implements DomainEvent {}
