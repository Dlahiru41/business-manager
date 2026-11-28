package com.bmp.finance.application;

import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Finance-internal: a close was accepted (202); the close runs from the event publication registry, so it survives a
 * restart and is retried like any listener (ADR-032 §9). Not part of the module's API.
 */
public record PeriodCloseRequested(
        UUID eventId,
        TenantId tenantId,
        Instant occurredAt,
        UUID aggregateId,
        long aggregateVersion,
        EventMetadata metadata,
        UUID operationId,
        String period,
        List<UUID> acknowledgedBillIds)
        implements DomainEvent {

    public PeriodCloseRequested {
        acknowledgedBillIds = List.copyOf(acknowledgedBillIds);
    }
}
