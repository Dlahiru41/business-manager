package com.bmp.inventory.api.events;

import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.LocationId;
import com.bmp.shared.id.TenantId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A count's variances were booked (INV-12) — {@code bmp.inventory.stock-count-posted.v1}. */
public record StockCountPosted(
        UUID eventId,
        TenantId tenantId,
        Instant occurredAt,
        UUID aggregateId,
        long aggregateVersion,
        EventMetadata metadata,
        UUID countId,
        LocationId locationId,
        String currency,
        List<StockMovementLine> lines,
        BigDecimal totalVarianceCost)
        implements DomainEvent {

    public StockCountPosted {
        lines = List.copyOf(lines);
    }
}
