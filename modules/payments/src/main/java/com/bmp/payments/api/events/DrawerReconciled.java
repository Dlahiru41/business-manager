package com.bmp.payments.api.events;

import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.TenantId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A drawer was counted and reconciled (PAY-04); {@code variance = counted − expected}, negative when short. */
public record DrawerReconciled(
        UUID eventId,
        TenantId tenantId,
        Instant occurredAt,
        UUID aggregateId,
        long aggregateVersion,
        EventMetadata metadata,
        UUID sessionId,
        UUID registerId,
        BigDecimal expected,
        BigDecimal counted,
        BigDecimal variance,
        String currency,
        Instant reconciledAt)
        implements DomainEvent {}
