package com.bmp.tenant.api.events;

import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.LocationId;
import com.bmp.shared.id.TenantId;
import java.time.Instant;
import java.util.UUID;

/**
 * A location was created ({@code bmp.tenant.location-created.v1}); inventory keeps a local replica.
 *
 * @param locationId the new location
 * @param name its name
 * @param type SHOP, STORE or WAREHOUSE
 */
public record LocationCreated(
        UUID eventId,
        TenantId tenantId,
        Instant occurredAt,
        UUID aggregateId,
        long aggregateVersion,
        EventMetadata metadata,
        LocationId locationId,
        String name,
        String type)
        implements DomainEvent {}
