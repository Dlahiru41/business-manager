package com.bmp.pricing.domain.model;

import com.bmp.pricing.domain.error.PricingErrorCode;
import com.bmp.shared.error.BusinessRuleViolation;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Map;
import java.util.Objects;

/**
 * How a price rule changes a unit price: a percentage off or of markup, an amount off, or a fixed price per sold unit.
 * The result is never below zero (PRC-06).
 *
 * @param type the kind of change
 * @param value a percentage (0-100 for PERCENT_OFF) or an amount in the list currency
 */
@Embeddable
public record RuleEffect(
        @Enumerated(EnumType.STRING) @Column(name = "effect_type", nullable = false)
        EffectType type,

        @Column(name = "effect_value", nullable = false, precision = 19, scale = 4)
        BigDecimal value) {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public RuleEffect {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(value, "value");
        if (value.signum() < 0 || (type == EffectType.PERCENT_OFF && value.compareTo(HUNDRED) > 0)) {
            throw new BusinessRuleViolation(
                    PricingErrorCode.INVALID_RULE_EFFECT, Map.of("type", type, "value", value.toPlainString()));
        }
    }

    /** The unit price after this effect, at full precision, never negative. */
    public BigDecimal apply(BigDecimal unitPrice) {
        BigDecimal result = switch (type) {
            case PERCENT_OFF ->
                unitPrice.multiply(BigDecimal.ONE.subtract(value.divide(HUNDRED, MathContext.DECIMAL128)));
            case MARKUP_PERCENT ->
                unitPrice.multiply(BigDecimal.ONE.add(value.divide(HUNDRED, MathContext.DECIMAL128)));
            case AMOUNT_OFF -> unitPrice.subtract(value);
            case FIXED_PRICE -> value;
        };
        return result.signum() < 0 ? BigDecimal.ZERO : result;
    }
}
