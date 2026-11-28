package com.bmp.reporting.application;

import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.TenantId;
import java.time.Instant;
import java.util.UUID;

/** Reporting-internal: a rebuild was accepted (202) and runs from the event publication registry (ADR-033 §5). */
public record RebuildRequested(
        UUID eventId,
        TenantId tenantId,
        Instant occurredAt,
        UUID aggregateId,
        long aggregateVersion,
        EventMetadata metadata,
        UUID operationId)
        implements DomainEvent {}
