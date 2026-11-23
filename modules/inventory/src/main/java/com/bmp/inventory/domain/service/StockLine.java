package com.bmp.inventory.domain.service;

import com.bmp.inventory.domain.model.BalanceKey;
import com.bmp.inventory.domain.model.MovementType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A movement to post, in base units on one balance.
 *
 * @param qtyBase in > 0, out < 0 (INV-02)
 * @param cost incoming stock's total cost; {@code null} for "at current cost" (outgoing stock always is)
 * @param mayGoNegative false when the negative stock policy blocks this movement (INV-08); sales always may (INV-07)
 */
public record StockLine(
        BalanceKey key,
        MovementType type,
        BigDecimal qtyBase,
        @Nullable BigDecimal cost,
        boolean mayGoNegative,
        String sourceType,
        @Nullable UUID sourceId,
        @Nullable UUID sourceLineId,
        @Nullable String reasonCode,
        Instant occurredAt) {

    public StockLine {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
        if (qtyBase.signum() == 0) {
            throw new IllegalArgumentException("A movement moves stock");
        }
        if (cost != null && cost.signum() < 0) {
            throw new IllegalArgumentException("A cost is not negative");
        }
    }

    public boolean isInbound() {
        return qtyBase.signum() > 0;
    }
}
