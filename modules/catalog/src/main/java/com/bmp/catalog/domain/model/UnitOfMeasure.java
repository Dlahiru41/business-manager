package com.bmp.catalog.domain.model;

import com.bmp.shared.error.FieldError;
import com.bmp.shared.error.ValidationFailure;
import com.bmp.shared.quantity.UnitCode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A unit of measure (CAT-01): a platform standard unit shared by every tenant ({@code tenantId == null}) or a tenant's
 * own. Units of one dimension convert through their factor to the dimension's reference unit (kg, l, m, piece); a
 * unit without a factor (e.g. {@code pack}) converts only through packaging units and product conversions. Units are
 * immutable.
 *
 * @param id the unit's id
 * @param tenantId the owning tenant, or {@code null} for a standard unit
 * @param code unique across the standard units and the tenant's own, e.g. {@code kg}
 * @param name display name
 * @param dimension the physical dimension
 * @param toSiFactor factor to the dimension's reference unit (g = 0.001), or {@code null}
 */
public record UnitOfMeasure(
        UUID id,
        @Nullable UUID tenantId,
        String code,
        String name,
        Dimension dimension,
        @Nullable BigDecimal toSiFactor) {

    public UnitOfMeasure {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(dimension, "dimension");
    }

    /** A tenant's own unit; the code must be well formed and the factor, when given, positive. */
    public static UnitOfMeasure custom(
            UUID id, UUID tenantId, String code, String name, Dimension dimension, @Nullable BigDecimal toSiFactor) {
        try {
            UnitCode.of(code);
        } catch (IllegalArgumentException e) {
            throw new ValidationFailure(List.of(new FieldError("code", "PATTERN", "must be a unit code")));
        }
        if (toSiFactor != null && toSiFactor.signum() <= 0) {
            throw new ValidationFailure(List.of(new FieldError("toSiFactor", "POSITIVE", "must be greater than 0")));
        }
        return new UnitOfMeasure(id, tenantId, code, name, dimension, toSiFactor);
    }

    public boolean isStandard() {
        return tenantId == null;
    }

    /** Whether this unit converts to {@code other} by factors alone: same dimension and both have a factor. */
    public boolean convertsBySiFactorTo(UnitOfMeasure other) {
        return dimension == other.dimension && toSiFactor != null && other.toSiFactor != null;
    }
}
