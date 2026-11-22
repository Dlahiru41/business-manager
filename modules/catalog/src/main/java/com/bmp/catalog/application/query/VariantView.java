package com.bmp.catalog.application.query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A variant as the API shows it; {@code packagingUnits} is null when they were not asked for. */
public record VariantView(
        UUID id,
        UUID productId,
        String sku,
        String name,
        List<AttributeValue> attributeValues,
        List<String> stockStates,
        String sellMode,
        String status,
        boolean sellable,
        boolean purchasable,
        @Nullable List<PackagingUnitView> packagingUnits,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public VariantView {
        attributeValues = List.copyOf(attributeValues);
        stockStates = List.copyOf(stockStates);
        packagingUnits = packagingUnits != null ? List.copyOf(packagingUnits) : null;
    }

    /** The same variant without its packaging units. */
    public VariantView withoutUnits() {
        return new VariantView(
                id,
                productId,
                sku,
                name,
                attributeValues,
                stockStates,
                sellMode,
                status,
                sellable,
                purchasable,
                null,
                version,
                createdAt,
                updatedAt);
    }

    /** One attribute value of the variant. */
    public record AttributeValue(
            UUID attributeId, String attributeCode, UUID valueId, String valueCode, String value) {}
}
