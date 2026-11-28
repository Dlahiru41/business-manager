package com.bmp.finance.api.events;

import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.TenantId;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A fiscal period was closed (FIN-06) — {@code bmp.finance.period-closed.v1}. */
public record PeriodClosed(
        UUID eventId,
        TenantId tenantId,
        Instant occurredAt,
        UUID aggregateId,
        long aggregateVersion,
        EventMetadata metadata,
        String period,
        boolean yearEnd,
        @Nullable UUID closingJournalId,
        Instant closedAt)
        implements DomainEvent {}
