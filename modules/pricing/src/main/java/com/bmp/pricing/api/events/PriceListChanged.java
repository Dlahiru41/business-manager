package com.bmp.pricing.api.events;

import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.PriceListId;
import com.bmp.shared.id.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Prices of a list changed in one new version (PRC-03) — {@code bmp.pricing.price-list-changed.v1}. Carries every
 * changed price, so sales and sync update their caches without calling back.
 *
 * @param priceListId the list
 * @param newVersion the list version the changes belong to
 * @param code the list code
 * @param channel RETAIL, WHOLESALE or ANY
 * @param currency the list currency
 * @param taxInclusive whether its prices include tax
 * @param prices the changed prices
 */
public record PriceListChanged(
        UUID eventId,
        TenantId tenantId,
        Instant occurredAt,
        UUID aggregateId,
        long aggregateVersion,
        EventMetadata metadata,
        PriceListId priceListId,
        int newVersion,
        String code,
        String channel,
        String currency,
        boolean taxInclusive,
        List<PriceChangeSnapshot> prices)
        implements DomainEvent {

    public PriceListChanged {
        prices = List.copyOf(prices);
    }
}
