package com.bmp.payments.api.events;

import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.TenantId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Money was given back against a payment (PAY-03): for a return, a void or at the counter. */
public record PaymentRefunded(
        UUID eventId,
        TenantId tenantId,
        Instant occurredAt,
        UUID aggregateId,
        long aggregateVersion,
        EventMetadata metadata,
        UUID refundId,
        UUID paymentId,
        @Nullable UUID billId,
        @Nullable UUID creditNoteId,
        @Nullable UUID customerId,
        BigDecimal amount,
        String method,
        String reason,
        @Nullable UUID drawerSessionId,
        String currency,
        Instant refundedAt)
        implements DomainEvent {}
