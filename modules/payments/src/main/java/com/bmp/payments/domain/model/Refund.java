package com.bmp.payments.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Money given back against a payment, as the {@code refund} ledger keeps it (PAY-03): never changed. */
public record Refund(
        UUID id,
        UUID paymentId,
        BigDecimal amount,
        PaymentMethod method,
        String currency,
        String reason,
        @Nullable UUID creditNoteId,
        @Nullable UUID sourceEventId,
        @Nullable UUID registerId,
        @Nullable UUID drawerSessionId,
        LocalDate businessDate,
        Instant createdAt) {

    public Refund {
        Objects.requireNonNull(paymentId, "paymentId");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("A refund gives money back");
        }
    }
}
