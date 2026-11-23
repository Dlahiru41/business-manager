package com.bmp.inventory.api.events;

import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.LocationId;
import com.bmp.shared.id.TenantId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A transformation ran (INV-09) — {@code bmp.inventory.stock-transformed.v1}: Σ input cost = Σ output cost. */
public record StockTransformed(
        UUID eventId,
        TenantId tenantId,
        Instant occurredAt,
        UUID aggregateId,
        long aggregateVersion,
        EventMetadata metadata,
        UUID runId,
        UUID definitionId,
        int defVersion,
        String kind,
        LocationId locationId,
        String currency,
        List<StockMovementLine> inputs,
        List<StockMovementLine> outputs,
        BigDecimal yieldVarianceQty,
        BigDecimal totalCost,
        List<StockMovementLine> corrections)
        implements DomainEvent {

    public StockTransformed {
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
        corrections = List.copyOf(corrections);
    }
}
