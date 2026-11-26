package com.bmp.sales.api.events;

import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.LocationId;
import com.bmp.shared.id.TenantId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Lines of a bill came back with a credit note (SAL-10). */
public record SaleReturned(
        UUID eventId,
        TenantId tenantId,
        Instant occurredAt,
        UUID aggregateId,
        long aggregateVersion,
        EventMetadata metadata,
        UUID creditNoteId,
        String number,
        UUID billId,
        String billNumber,
        @Nullable UUID customerId,
        LocationId locationId,
        UUID registerId,
        UUID refundRegisterId,
        String currency,
        String reason,
        BigDecimal total,
        BigDecimal taxTotal,
        List<ReturnedLine> lines,
        List<RefundTender> refundTenders,
        Instant returnedAt)
        implements DomainEvent {

    public SaleReturned {
        lines = List.copyOf(lines);
        refundTenders = List.copyOf(refundTenders);
    }
}
