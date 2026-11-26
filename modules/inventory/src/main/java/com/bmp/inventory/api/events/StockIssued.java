package com.bmp.inventory.api.events;

import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.LocationId;
import com.bmp.shared.id.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Stock left through a sale or came back through a return (restocked lines, at their issue cost) or a void (T03-04).
 * Lines are signed movements; {@code sourceLineId} is the bill line, so finance and reporting get the cost of every
 * bill line. {@code corrections} carry the true-ups of returns into negative stock.
 *
 * @param sourceType SALE, SALE_RETURN or SALE_VOID
 * @param sourceId the bill, or the credit note of a return
 */
public record StockIssued(
        UUID eventId,
        TenantId tenantId,
        Instant occurredAt,
        UUID aggregateId,
        long aggregateVersion,
        EventMetadata metadata,
        String sourceType,
        UUID sourceId,
        UUID billId,
        LocationId locationId,
        String currency,
        List<StockMovementLine> lines,
        List<StockMovementLine> corrections)
        implements DomainEvent {

    public StockIssued {
        lines = List.copyOf(lines);
        corrections = List.copyOf(corrections);
    }
}
