package com.bmp.sales.api.events;

import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Money going back for a return; payments refunds it once per {@code refundId} (PAY-03).
 *
 * @param originalTenderId the bill tender it pays back, when the bill had one method
 */
public record RefundTender(
        UUID refundId,
        String method,
        BigDecimal amount,
        @Nullable String reference,
        @Nullable UUID originalTenderId) {}
