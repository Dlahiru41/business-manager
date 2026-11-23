package com.bmp.inventory.domain.service;

import com.bmp.inventory.domain.model.BalanceKey;
import com.bmp.inventory.domain.model.MovementType;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.PlatformErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
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
        // Reachable from requests (a quantity of 0, or one that is 0 at base-unit scale): a 422, not a 500.
        if (qtyBase.signum() == 0) {
            throw new BusinessRuleViolation(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A movement moves stock: the quantity is 0 in base units",
                    Map.of("variantId", key.variantId()));
        }
        if (cost != null && cost.signum() < 0) {
            throw new BusinessRuleViolation(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A cost is not negative",
                    Map.of("variantId", key.variantId()));
        }
    }

    public boolean isInbound() {
        return qtyBase.signum() > 0;
    }
}
