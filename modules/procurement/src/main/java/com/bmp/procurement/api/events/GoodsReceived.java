package com.bmp.procurement.api.events;

import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.LocationId;
import com.bmp.shared.id.PartyId;
import com.bmp.shared.id.TenantId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A goods receipt was posted (PRO-04) — {@code bmp.procurement.goods-received.v1}. Inventory books its lines as
 * stock; finance posts Dr Inventory / Cr GRNI for its total.
 *
 * @param correctsReceiptId set on a return to the supplier (negative lines, PRO-02)
 */
public record GoodsReceived(
        UUID eventId,
        TenantId tenantId,
        Instant occurredAt,
        UUID aggregateId,
        long aggregateVersion,
        EventMetadata metadata,
        UUID receiptId,
        String number,
        PartyId supplierId,
        LocationId locationId,
        @Nullable UUID purchaseOrderId,
        @Nullable UUID correctsReceiptId,
        Instant receivedAt,
        String currency,
        BigDecimal total,
        List<GoodsReceivedLine> lines)
        implements DomainEvent {

    public GoodsReceived {
        lines = List.copyOf(lines);
    }
}
