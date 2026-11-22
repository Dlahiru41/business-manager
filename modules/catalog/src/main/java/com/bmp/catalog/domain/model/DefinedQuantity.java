package com.bmp.catalog.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * A packaging unit's size in a unit other than the base unit (the oil can: 18 kg). Its {@code qtyInBase} follows
 * through the unit converter and is recomputed when the product's conversions change (CAT-14).
 *
 * @param value the size, positive
 * @param unitId the unit the size is given in
 */
@Embeddable
public record DefinedQuantity(
        @Column(name = "defined_qty", precision = 20, scale = 6)
        BigDecimal value,

        @Column(name = "defined_uom_id") UUID unitId) {

    public DefinedQuantity {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(unitId, "unitId");
    }
}
