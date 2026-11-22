package com.bmp.pricing.domain.model;

import com.bmp.shared.error.FieldError;
import com.bmp.shared.error.ValidationFailure;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What a price is for within one list: a packaging unit of a variant from a minimum quantity (PRC-02). Windows of the
 * same key never overlap. The minimum is positive; a price applies to quantities from it upwards, so selling a
 * fraction of a unit (0.75 kg) needs a price whose minimum is that small (ADR-021).
 *
 * @param variantId the variant
 * @param packagingUnitId its packaging unit
 * @param minQty the minimum quantity in that unit, at storage scale
 */
public record PriceKey(UUID variantId, UUID packagingUnitId, BigDecimal minQty) {

    public PriceKey {
        Objects.requireNonNull(variantId, "variantId");
        Objects.requireNonNull(packagingUnitId, "packagingUnitId");
        minQty = minQty.setScale(Price.QTY_SCALE, RoundingMode.HALF_UP);
        if (minQty.signum() <= 0) {
            throw new ValidationFailure(List.of(new FieldError("minQty", "RANGE", "must be greater than 0")));
        }
    }
}
