package com.bmp.finance.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Cash taken from a drawer to the bank; never changed. */
public record CashDrop(
        UUID id,
        UUID drawerSessionId,
        UUID registerId,
        BigDecimal amount,
        String currency,
        @Nullable String reference,
        Instant droppedAt,
        UUID journalId) {}
