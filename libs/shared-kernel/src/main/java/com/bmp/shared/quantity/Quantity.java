package com.bmp.shared.quantity;

import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.PlatformErrorCode;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * An amount of something in one unit. Stock is signed (+ in, − out), so negative values are allowed. Conversion to a
 * variant's base unit goes through the catalog's packaging units and product conversions, never through this type.
 * Equality ignores scale; mixing units throws {@code UNIT_MISMATCH}.
 *
 * @param value the amount, any scale
 * @param unit the unit the value is expressed in
 */
public record Quantity(BigDecimal value, UnitCode unit) implements Comparable<Quantity> {

    public Quantity {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(unit, "unit");
    }

    public static Quantity of(BigDecimal value, UnitCode unit) {
        return new Quantity(value, unit);
    }

    /** Parses a decimal string such as {@code "0.750"}. */
    public static Quantity of(String value, UnitCode unit) {
        return new Quantity(new BigDecimal(value), unit);
    }

    public static Quantity zero(UnitCode unit) {
        return new Quantity(BigDecimal.ZERO, unit);
    }

    public Quantity plus(Quantity other) {
        requireSameUnit(other);
        return new Quantity(value.add(other.value), unit);
    }

    public Quantity minus(Quantity other) {
        requireSameUnit(other);
        return new Quantity(value.subtract(other.value), unit);
    }

    /** Multiplies at full precision, keeping the unit (e.g. runs × quantity per run). */
    public Quantity times(BigDecimal factor) {
        return new Quantity(value.multiply(factor), unit);
    }

    public Quantity negate() {
        return new Quantity(value.negate(), unit);
    }

    public boolean isZero() {
        return value.signum() == 0;
    }

    public boolean isPositive() {
        return value.signum() > 0;
    }

    public boolean isNegative() {
        return value.signum() < 0;
    }

    /** Same unit and numerically equal value, regardless of scale. */
    public boolean isEqualTo(Quantity other) {
        return unit.equals(other.unit) && value.compareTo(other.value) == 0;
    }

    @Override
    public int compareTo(Quantity other) {
        requireSameUnit(other);
        return value.compareTo(other.value);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return o instanceof Quantity other && isEqualTo(other);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value.stripTrailingZeros(), unit);
    }

    @Override
    public String toString() {
        return value.toPlainString() + " " + unit;
    }

    private void requireSameUnit(Quantity other) {
        if (!unit.equals(other.unit)) {
            throw new BusinessRuleViolation(
                    PlatformErrorCode.UNIT_MISMATCH, Map.of("unit", unit.code(), "otherUnit", other.unit.code()));
        }
    }
}
