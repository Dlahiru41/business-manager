package com.bmp.payments.domain.model;

import com.bmp.payments.domain.error.PaymentsErrorCode;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.shared.security.CardNumbers;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Money taken, as the {@code payment} ledger keeps it (PAY-01, PAY-02): never changed, refunds are rows of their own.
 *
 * @param kind BILL for a bill tender, ACCOUNT for a collection on a customer's account
 * @param changeGiven cash given back ({@code tendered − amount})
 */
public record Payment(
        UUID id,
        PaymentKind kind,
        @Nullable UUID billId,
        @Nullable UUID tenderId,
        @Nullable UUID customerId,
        PaymentMethod method,
        BigDecimal amount,
        String currency,
        @Nullable BigDecimal tendered,
        @Nullable BigDecimal changeGiven,
        @Nullable String reference,
        @Nullable UUID registerId,
        @Nullable UUID drawerSessionId,
        @Nullable UUID deviceId,
        LocalDate businessDate,
        Instant capturedAt) {

    public Payment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(method, "method");
        if (amount.signum() <= 0) {
            throw new BusinessRuleViolation(
                    PlatformErrorCode.VALIDATION_FAILED, "A payment takes money", Map.of("field", "amount"));
        }
        if (kind == PaymentKind.BILL && (billId == null || tenderId == null)) {
            throw new IllegalArgumentException("A bill payment names its bill and tender");
        }
    }

    /**
     * Rules PAY-02, PAY-05, SAL-06 for money taken at the counter: cash covers the amount, CARD and BANK_TRANSFER carry
     * a reference when the tenant requires one, and no reference looks like a card number.
     *
     * @return the change to give back, for cash; {@code null} otherwise
     */
    public static @Nullable BigDecimal checkTender(
            PaymentMethod method,
            BigDecimal amount,
            @Nullable BigDecimal tendered,
            @Nullable String reference,
            boolean referenceRequired) {
        checkReference(reference);
        if (method == PaymentMethod.CASH) {
            BigDecimal given = tendered != null ? tendered : amount;
            if (given.compareTo(amount) < 0) {
                throw new BusinessRuleViolation(
                        PaymentsErrorCode.TENDERED_TOO_LOW,
                        Map.of("amount", amount.toPlainString(), "tendered", given.toPlainString()));
            }
            return given.subtract(amount);
        }
        if (referenceRequired && method.needsReference() && (reference == null || reference.isBlank())) {
            throw new BusinessRuleViolation(PaymentsErrorCode.REFERENCE_REQUIRED, Map.of("method", method.name()));
        }
        return null;
    }

    /** Rule PAY-05: nothing that looks like a card number is stored. */
    public static void checkReference(@Nullable String text) {
        if (CardNumbers.containsCardNumber(text)) {
            throw new BusinessRuleViolation(PaymentsErrorCode.CARD_DATA_NOT_ALLOWED, Map.of());
        }
    }
}
