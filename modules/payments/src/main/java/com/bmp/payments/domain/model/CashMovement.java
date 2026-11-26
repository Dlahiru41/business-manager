package com.bmp.payments.domain.model;

import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.PlatformErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Cash in or out of a drawer, as the {@code cash_movement} ledger keeps it (PAY-04): never changed.
 *
 * @param amount positive; the type gives the direction
 * @param sourceType PAYMENT, REFUND, DRAWER or MANUAL
 */
public record CashMovement(
        UUID id,
        UUID drawerSessionId,
        CashMovementType type,
        BigDecimal amount,
        @Nullable String reason,
        String sourceType,
        @Nullable UUID sourceId,
        Instant occurredAt) {

    public CashMovement {
        Objects.requireNonNull(type, "type");
        if (amount.signum() < 0) {
            throw new BusinessRuleViolation(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A cash movement amount is not negative",
                    Map.of("field", "amount"));
        }
    }

    /** The amount with the direction's sign: into the drawer positive, out of it negative. */
    public BigDecimal signed() {
        return type.into() ? amount : amount.negate();
    }
}
