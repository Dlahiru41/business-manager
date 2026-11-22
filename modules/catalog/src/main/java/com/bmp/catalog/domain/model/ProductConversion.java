package com.bmp.catalog.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * A product-specific factor between units of different dimensions: {@code 1 from = factor to} (oil: 1 kg = 1,086.96
 * ml). The inverse is implied. Changing it changes future conversions only (CAT-14).
 *
 * @param fromUnitId the unit converted from
 * @param toUnitId the unit converted to
 * @param factor how many {@code to} units one {@code from} unit is; positive
 * @param source where the factor comes from
 */
@Embeddable
public record ProductConversion(
        @Column(name = "from_uom_id", nullable = false) UUID fromUnitId,
        @Column(name = "to_uom_id", nullable = false) UUID toUnitId,

        @Column(nullable = false, precision = 20, scale = 10)
        BigDecimal factor,

        @Enumerated(EnumType.STRING) @Column(nullable = false)
        ConversionSource source) {

    public ProductConversion {
        Objects.requireNonNull(fromUnitId, "fromUnitId");
        Objects.requireNonNull(toUnitId, "toUnitId");
        Objects.requireNonNull(factor, "factor");
        Objects.requireNonNull(source, "source");
    }

    /** Whether this conversion links the two units, in either direction. */
    public boolean links(UUID a, UUID b) {
        return (fromUnitId.equals(a) && toUnitId.equals(b)) || (fromUnitId.equals(b) && toUnitId.equals(a));
    }
}
