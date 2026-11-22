package com.bmp.shared.money;

import java.math.BigDecimal;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A non-negative percentage such as {@code 12.50} (meaning 12.5 %). Used for discounts, tax rates and cost shares.
 * Equality ignores scale.
 *
 * @param value the percentage points, e.g. {@code 5.00} for five percent
 */
public record Percentage(BigDecimal value) {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public Percentage {
        Objects.requireNonNull(value, "value");
        if (value.signum() < 0) {
            throw new IllegalArgumentException("Percentage must not be negative: " + value);
        }
    }

    /** Parses a decimal string such as {@code "12.50"}. */
    public static Percentage of(String value) {
        return new Percentage(new BigDecimal(value));
    }

    /** The percentage as a fraction, e.g. {@code 0.1250} for 12.50 %. Exact. */
    public BigDecimal asFraction() {
        return value.movePointLeft(2);
    }

    /** This percentage of an amount, at full precision (round at line level afterwards). */
    public Money of(Money amount) {
        return amount.times(asFraction());
    }

    public boolean isGreaterThan(Percentage other) {
        return value.compareTo(other.value) > 0;
    }

    public boolean isAtMostHundred() {
        return value.compareTo(HUNDRED) <= 0;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return o instanceof Percentage other && value.compareTo(other.value) == 0;
    }

    @Override
    public int hashCode() {
        return value.stripTrailingZeros().hashCode();
    }

    @Override
    public String toString() {
        return value.toPlainString() + "%";
    }
}
