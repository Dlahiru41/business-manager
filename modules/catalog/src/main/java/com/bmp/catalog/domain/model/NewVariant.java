package com.bmp.catalog.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What a new variant is made of.
 *
 * @param sku unique per tenant; stored upper-case (CAT-06)
 * @param name display name, e.g. {@code Eggs Red L}
 * @param attributeValueIds its combination of attribute values (empty for single-variant products)
 * @param stockStates the states its stock is kept in
 * @param sellMode how selling takes stock
 * @param packagingUnits its packaging units, exactly one of them the base unit
 */
public record NewVariant(
        String sku,
        String name,
        List<UUID> attributeValueIds,
        List<String> stockStates,
        SellMode sellMode,
        List<PackagingUnitDraft> packagingUnits) {

    public NewVariant {
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(sellMode, "sellMode");
        attributeValueIds = List.copyOf(attributeValueIds);
        stockStates = List.copyOf(stockStates);
        packagingUnits = List.copyOf(packagingUnits);
    }
}
