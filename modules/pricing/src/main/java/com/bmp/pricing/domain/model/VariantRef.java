package com.bmp.pricing.domain.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The pricing replica of a catalog variant, kept from catalog events (T01-05): what pricing needs to validate and
 * resolve prices without calling the catalog.
 *
 * @param variantId the variant
 * @param productId its product (price rules may target products)
 * @param taxCategoryId its product's tax category, if any (PRC-07)
 * @param active whether the variant is active
 * @param aggregateVersion the catalog version of the snapshot, to ignore older events
 * @param units its packaging units
 */
public record VariantRef(
        UUID variantId,
        UUID productId,
        @Nullable UUID taxCategoryId,
        boolean active,
        long aggregateVersion,
        List<Unit> units) {

    public VariantRef {
        Objects.requireNonNull(variantId, "variantId");
        Objects.requireNonNull(productId, "productId");
        units = List.copyOf(units);
    }

    public Optional<Unit> unit(UUID packagingUnitId) {
        return units.stream().filter(u -> u.id().equals(packagingUnitId)).findFirst();
    }

    public Optional<Unit> baseUnit() {
        return units.stream().filter(Unit::isBase).findFirst();
    }

    /**
     * A packaging unit of the variant.
     *
     * @param id the packaging unit
     * @param code its code
     * @param qtyInBase base units per packaging unit
     * @param isBase whether it is the base unit
     * @param canSell may be sold
     * @param active whether it is active
     */
    public record Unit(UUID id, String code, BigDecimal qtyInBase, boolean isBase, boolean canSell, boolean active) {

        /** Active and sold. */
        public boolean sellable() {
            return active && canSell;
        }
    }
}
