package com.bmp.finance.api.events;

import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.TenantId;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** An expense was recorded and posted (FIN-09) — {@code bmp.finance.expense-recorded.v1}. */
public record ExpenseRecorded(
        UUID eventId,
        TenantId tenantId,
        Instant occurredAt,
        UUID aggregateId,
        long aggregateVersion,
        EventMetadata metadata,
        UUID expenseId,
        UUID categoryId,
        UUID accountId,
        BigDecimal amount,
        String currency,
        String paidFrom,
        LocalDate expenseDate,
        @Nullable UUID drawerSessionId,
        @Nullable UUID supplierId,
        UUID journalId)
        implements DomainEvent {}
