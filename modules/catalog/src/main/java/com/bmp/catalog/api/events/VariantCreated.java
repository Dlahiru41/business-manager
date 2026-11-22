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
 * A variant was created ({@code bmp.catalog.variant-created.v1}): a full snapshot with its packaging units, so pricing,
 * inventory, sales and sync keep replicas (CAT-13).
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
public record VariantCreated(
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

    public VariantCreated {
        attributeValues = List.copyOf(attributeValues);
        stockStates = List.copyOf(stockStates);
        packagingUnits = List.copyOf(packagingUnits);
    }
}
