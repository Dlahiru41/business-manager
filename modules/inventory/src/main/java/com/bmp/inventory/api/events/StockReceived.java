package com.bmp.inventory.api.events;

import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.LocationId;
import com.bmp.shared.id.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Stock came in from a goods receipt, an opening balance or a transfer — {@code bmp.inventory.stock-received.v1}.
 * {@code corrections} true up the cost of stock issued below zero (INV-03, INV-04). */
public record StockReceived(
        UUID eventId,
        TenantId tenantId,
        Instant occurredAt,
        UUID aggregateId,
        long aggregateVersion,
        EventMetadata metadata,
        String sourceType,
        UUID sourceId,
        LocationId locationId,
        String currency,
        List<StockMovementLine> lines,
        List<StockMovementLine> corrections)
        implements DomainEvent {

    public StockReceived {
        lines = List.copyOf(lines);
        corrections = List.copyOf(corrections);
    }
}
