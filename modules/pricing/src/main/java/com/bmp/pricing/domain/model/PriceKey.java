package com.bmp.pricing.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

/**
 * What a price is for within one list: a packaging unit of a variant from a minimum quantity (PRC-02). Windows of the
 * same key never overlap.
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
    }
}
