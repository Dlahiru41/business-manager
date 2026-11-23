package com.bmp.inventory.api.events;

import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.LocationId;
import com.bmp.shared.id.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Stock was lost (INV-11) — {@code bmp.inventory.stock-adjusted.v1}: wastage movements with their cost. */
public record StockAdjusted(
        UUID eventId,
        TenantId tenantId,
        Instant occurredAt,
        UUID aggregateId,
        long aggregateVersion,
        EventMetadata metadata,
        UUID adjustmentId,
        String reason,
        @Nullable String note,
        LocationId locationId,
        String currency,
        List<StockMovementLine> lines)
        implements DomainEvent {

    public StockAdjusted {
        lines = List.copyOf(lines);
    }
}
