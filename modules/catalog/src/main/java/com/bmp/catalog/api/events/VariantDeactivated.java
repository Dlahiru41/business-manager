package com.bmp.catalog.api.events;

import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.ProductId;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.VariantId;
import java.time.Instant;
import java.util.UUID;

/**
 * A variant was deactivated ({@code bmp.catalog.variant-deactivated.v1}, CAT-11). It is never deleted; reactivation
 * comes as {@link VariantUpdated} with status ACTIVE.
 *
 * @param variantId the variant
 * @param productId its product
 * @param sku its SKU
 */
public record VariantDeactivated(
        UUID eventId,
        TenantId tenantId,
        Instant occurredAt,
        UUID aggregateId,
        long aggregateVersion,
        EventMetadata metadata,
        VariantId variantId,
        ProductId productId,
        String sku)
        implements DomainEvent {}
