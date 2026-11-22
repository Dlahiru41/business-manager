package com.bmp.pricing.application.query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A list version; {@code changes} only when one version is read or just published. */
public record PriceListVersionView(
        UUID priceListId,
        int version,
        Instant publishedAt,
        @Nullable UUID publishedBy,
        @Nullable String note,
        int changeCount,
        @Nullable List<PriceChangeView> changes) {

    public PriceListVersionView {
        changes = changes != null ? List.copyOf(changes) : null;
    }
}
