package com.bmp.pricing.application.query;

import com.bmp.pricing.domain.model.PriceList;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A price list as the API shows it. */
public record PriceListView(
        UUID id,
        String code,
        String name,
        String channel,
        @Nullable UUID customerGroupId,
        @Nullable UUID customerId,
        String currency,
        boolean taxInclusive,
        int priority,
        int currentVersion,
        String status,
        long version,
        Instant createdAt) {

    public static PriceListView of(PriceList list) {
        return new PriceListView(
                list.id(),
                list.code(),
                list.name(),
                list.channel().name(),
                list.customerGroupId(),
                list.customerId(),
                list.currency(),
                list.taxInclusive(),
                list.priority(),
                list.currentVersion(),
                list.status().name(),
                list.version(),
                list.createdAt());
    }
}
