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

/** A bill was completed online or synced from a till (SAL-02, SAL-08); offline bills carry their flags. */
public record SaleCompleted(
        UUID eventId,
        TenantId tenantId,
        Instant occurredAt,
        UUID aggregateId,
        long aggregateVersion,
        EventMetadata metadata,
        UUID billId,
        String number,
        String channel,
        @Nullable UUID customerId,
        LocationId locationId,
        UUID registerId,
        @Nullable UUID shiftId,
        @Nullable UUID deviceId,
        @Nullable UUID cashierId,
        @Nullable UUID priceListId,
        @Nullable Integer priceListVersion,
        java.time.LocalDate businessDate,
        Instant completedAt,
        String currency,
        boolean offline,
        List<String> flags,
        List<SaleLine> lines,
        SaleTotals totals,
        List<SaleTender> tenders)
        implements DomainEvent {

    public SaleCompleted {
        flags = List.copyOf(flags);
        lines = List.copyOf(lines);
        tenders = List.copyOf(tenders);
    }

    /** Σ of the tenders' amounts of a method. */
    public BigDecimal tendered(String method) {
        return tenders.stream()
                .filter(t -> t.method().equals(method))
                .map(SaleTender::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
