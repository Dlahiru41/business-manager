package com.bmp.finance.api.events;

import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.TenantId;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A customer has items past due by more than the grace days; at most once a week per customer (FIN-08) —
 * {@code bmp.finance.receivable-overdue.v1}.
 */
public record ReceivableOverdue(
        UUID eventId,
        TenantId tenantId,
        Instant occurredAt,
        UUID aggregateId,
        long aggregateVersion,
        EventMetadata metadata,
        UUID customerId,
        BigDecimal balance,
        BigDecimal overdueAmount,
        int daysOverdue,
        String currency,
        LocalDate asOf)
        implements DomainEvent {}
