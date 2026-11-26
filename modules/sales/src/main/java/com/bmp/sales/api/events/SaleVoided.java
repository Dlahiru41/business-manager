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

/** A bill was voided on its business date (SAL-09): payments reverses its tenders, inventory its stock. */
public record SaleVoided(
        UUID eventId,
        TenantId tenantId,
        Instant occurredAt,
        UUID aggregateId,
        long aggregateVersion,
        EventMetadata metadata,
        UUID billId,
        String number,
        @Nullable UUID customerId,
        LocationId locationId,
        UUID registerId,
        String currency,
        String reason,
        @Nullable UUID approvedBy,
        BigDecimal grandTotal,
        List<SaleTender> tenders,
        Instant voidedAt)
        implements DomainEvent {

    public SaleVoided {
        tenders = List.copyOf(tenders);
    }
}
