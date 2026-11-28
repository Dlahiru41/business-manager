package com.bmp.finance.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** An expense as recorded (FIN-09); never changed, a wrong one is reversed through its journal. */
public record Expense(
        UUID id,
        UUID categoryId,
        LocalDate expenseDate,
        BigDecimal amount,
        String currency,
        PaidFrom paidFrom,
        @Nullable UUID drawerSessionId,
        @Nullable UUID supplierId,
        @Nullable LocalDate dueDate,
        String description,
        List<String> attachmentKeys,
        UUID journalId,
        Instant createdAt) {

    public Expense {
        attachmentKeys = List.copyOf(attachmentKeys);
    }
}
