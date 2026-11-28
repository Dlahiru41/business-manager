package com.bmp.payments.api.events;

import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.TenantId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Money was taken: a bill tender other than CREDIT (PAY-01) or a collection on a customer's account (PAY-02).
 *
 * @param kind BILL or ACCOUNT
 * @param method CASH, CARD, BANK_TRANSFER, MOBILE_WALLET or CHEQUE
 */
public record PaymentCaptured(
        UUID eventId,
        TenantId tenantId,
        Instant occurredAt,
        UUID aggregateId,
        long aggregateVersion,
        EventMetadata metadata,
        UUID paymentId,
        String kind,
        @Nullable UUID billId,
        @Nullable UUID tenderId,
        @Nullable UUID customerId,
        String method,
        BigDecimal amount,
        @Nullable BigDecimal tendered,
        @Nullable BigDecimal changeGiven,
        @Nullable String reference,
        @Nullable UUID registerId,
        @Nullable UUID drawerSessionId,
        String currency,
        Instant capturedAt,
        List<Allocation> allocations)
        implements DomainEvent {

    public PaymentCaptured {
        allocations = List.copyOf(allocations);
    }

    /**
     * A bill an account collection pays, as the cashier chose (FIN-07); finance settles the rest oldest first.
     *
     * @param amount at most what the collection pays
     */
    public record Allocation(UUID billId, BigDecimal amount) {}
}
