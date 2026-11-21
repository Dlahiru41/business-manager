package com.bmp.sample.api.events;

import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.TenantId;
import java.time.Instant;
import java.util.UUID;

public record SampleThingRenamedV2(
        UUID eventId,
        TenantId tenantId,
        Instant occurredAt,
        UUID aggregateId,
        long aggregateVersion,
        EventMetadata metadata)
        implements DomainEvent {}
