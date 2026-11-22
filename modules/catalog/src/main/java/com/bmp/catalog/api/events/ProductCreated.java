package com.bmp.catalog.api.events;

import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.ProductId;
import com.bmp.shared.id.TenantId;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A product was created ({@code bmp.catalog.product-created.v1}); its variants follow as {@link VariantCreated}.
 *
 * @param productId the product
 * @param code unique per tenant
 * @param name display name
 * @param template the template it was created from
 * @param baseUnit the code of the unit its stock is kept in
 * @param tracking what its stock tracks
 * @param categoryId its category, if any
 * @param taxCategoryId its tax category (pricing), if any
 * @param status ACTIVE or INACTIVE
 */
public record ProductCreated(
        UUID eventId,
        TenantId tenantId,
        Instant occurredAt,
        UUID aggregateId,
        long aggregateVersion,
        EventMetadata metadata,
        ProductId productId,
        String code,
        String name,
        String template,
        String baseUnit,
        ProductTracking tracking,
        @Nullable UUID categoryId,
        @Nullable UUID taxCategoryId,
        String status)
        implements DomainEvent {}
