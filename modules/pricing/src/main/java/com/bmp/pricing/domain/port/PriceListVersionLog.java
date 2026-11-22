package com.bmp.pricing.domain.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Every version of a price list with its change summary (PRC-03), append-only. */
public interface PriceListVersionLog {

    void append(
            UUID priceListId,
            int version,
            Instant publishedAt,
            @Nullable UUID publishedBy,
            @Nullable String note,
            int changeCount,
            String changesJson);

    /** Versions newest first, before {@code beforeVersion} when given. */
    List<Entry> findPage(UUID priceListId, @Nullable Integer beforeVersion, int limit);

    Optional<Entry> find(UUID priceListId, int version);

    /**
     * One version.
     *
     * @param changesJson the changes as a JSON array
     */
    record Entry(
            UUID priceListId,
            int version,
            Instant publishedAt,
            @Nullable UUID publishedBy,
            @Nullable String note,
            int changeCount,
            String changesJson) {}
}
