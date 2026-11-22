package com.bmp.catalog.api.events;

import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.ProductId;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.VariantId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A variant, one of its packaging units or barcodes, or the product data it carries changed, or a product conversion
 * resized a unit (CAT-13, CAT-14) — {@code bmp.catalog.variant-updated.v1}. A full snapshot; consumers keep the one
 * with the highest {@code aggregateVersion}.
 *
 * @param variantId the variant
 * @param productId its product
 * @param sku unique per tenant
 * @param name display name
 * @param attributeValues its combination of attribute values
 * @param stockStates the states its stock is kept in
 * @param sellMode PREPACKED or BREAK_ON_SALE
 * @param status ACTIVE or INACTIVE
 * @param baseUnit the code of its base unit
 * @param taxCategoryId its product's tax category, if any
 * @param tracking what its product's stock tracks
 * @param packagingUnits its packaging units, base unit first
 */
public record VariantUpdated(
        UUID eventId,
        TenantId tenantId,
        Instant occurredAt,
        UUID aggregateId,
        long aggregateVersion,
        EventMetadata metadata,
        VariantId variantId,
        ProductId productId,
        String sku,
        String name,
        List<VariantAttributeValue> attributeValues,
        List<String> stockStates,
        String sellMode,
        String status,
        String baseUnit,
        @Nullable UUID taxCategoryId,
        ProductTracking tracking,
        List<PackagingUnitSnapshot> packagingUnits)
        implements DomainEvent {

    public VariantUpdated {
        attributeValues = List.copyOf(attributeValues);
        stockStates = List.copyOf(stockStates);
        packagingUnits = List.copyOf(packagingUnits);
    }
}
