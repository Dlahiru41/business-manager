package com.bmp.inventory.api.events;

import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.LocationId;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.VariantId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Available stock fell to or below the reorder level, once per crossing (INV-13) —
 * {@code bmp.inventory.stock-level-low.v1}.
 *
 * @param available Σ (on hand − reserved) in base units, every state and batch
 */
public record StockLevelLow(
        UUID eventId,
        TenantId tenantId,
        Instant occurredAt,
        UUID aggregateId,
        long aggregateVersion,
        EventMetadata metadata,
        VariantId variantId,
        LocationId locationId,
        BigDecimal available,
        BigDecimal reorderLevel,
        BigDecimal reorderQty,
        @Nullable UUID preferredSupplierId)
        implements DomainEvent {}
