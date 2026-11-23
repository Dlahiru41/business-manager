package com.bmp.procurement.api.events;

import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.PartyId;
import com.bmp.shared.id.TenantId;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A supplier invoice matched to posted receipts was posted (PRO-05) — {@code bmp.procurement.supplier-invoice-posted.v1}.
 *
 * @param receiptValue Σ the matched receipts' totals
 * @param priceVariance {@code subtotal − receiptValue}
 */
public record SupplierInvoicePosted(
        UUID eventId,
        TenantId tenantId,
        Instant occurredAt,
        UUID aggregateId,
        long aggregateVersion,
        EventMetadata metadata,
        UUID invoiceId,
        PartyId supplierId,
        String supplierRef,
        LocalDate invoiceDate,
        LocalDate dueDate,
        String currency,
        BigDecimal subtotal,
        BigDecimal tax,
        BigDecimal total,
        BigDecimal receiptValue,
        BigDecimal priceVariance,
        List<UUID> receiptIds)
        implements DomainEvent {

    public SupplierInvoicePosted {
        receiptIds = List.copyOf(receiptIds);
    }
}
