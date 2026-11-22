package com.bmp.shared.money;

import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.shared.quantity.Quantity;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * An amount of money in one currency. Arithmetic keeps full precision; call {@link #rounded(RoundingPolicy)} at line
 * level. Equality ignores scale ({@code 1.0 LKR} equals {@code 1.00 LKR}). Mixing currencies throws
 * {@code CURRENCY_MISMATCH}.
 *
 * @param amount the amount, any scale, may be negative (refunds, reversals)
 * @param currency the currency
 */
public record Money(BigDecimal amount, CurrencyCode currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
    }

    public static Money of(BigDecimal amount, CurrencyCode currency) {
        return new Money(amount, currency);
    }

    /** Parses a decimal string such as {@code "1250.00"}. */
    public static Money of(String amount, CurrencyCode currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    public static Money zero(CurrencyCode currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    /** Multiplies at full precision, e.g. unit price × factor. */
    public Money times(BigDecimal factor) {
        return new Money(amount.multiply(factor), currency);
    }

    /** Multiplies by a quantity's value at full precision, e.g. unit price × quantity. */
    public Money times(Quantity quantity) {
        return times(quantity.value());
    }

    public Money negate() {
        return new Money(amount.negate(), currency);
    }

    /** Rounds to the policy's scale and mode (line-level rounding). */
    public Money rounded(RoundingPolicy policy) {
        return new Money(policy.round(amount), currency);
    }

    /** Splits this amount by weights with the largest-remainder method; the parts always sum to this amount. */
    public List<Money> allocate(List<BigDecimal> weights) {
        return Allocation.largestRemainder(this, weights);
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    /** Same currency and numerically equal amount, regardless of scale. */
    public boolean isEqualTo(Money other) {
        return currency.equals(other.currency) && amount.compareTo(other.amount) == 0;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return o instanceof Money other && isEqualTo(other);
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount.stripTrailingZeros(), currency);
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency;
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new BusinessRuleViolation(
                    PlatformErrorCode.CURRENCY_MISMATCH,
                    Map.of("currency", currency.code(), "otherCurrency", other.currency.code()));
        }
    }
}
