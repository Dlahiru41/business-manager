package com.bmp.payments.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A drawer session as other modules see it (PAY-04).
 *
 * @param status OPEN, COUNTED or RECONCILED
 */
public record DrawerSessionInfo(
        UUID id,
        UUID registerId,
        String status,
        BigDecimal openingFloat,
        BigDecimal expectedCash,
        @Nullable BigDecimal countedCash,
        @Nullable BigDecimal variance,
        Instant openedAt) {}
