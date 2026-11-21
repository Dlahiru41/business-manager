package com.bmp.sample.api.events;

import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.TenantId;
import java.time.Instant;
import java.util.UUID;

/** A domain event of a stand-in module, for the audit listener tests. */
public record SampleRecorded(
        UUID eventId,
        TenantId tenantId,
        Instant occurredAt,
        UUID aggregateId,
        long aggregateVersion,
        EventMetadata metadata,
        String note)
        implements DomainEvent {}
