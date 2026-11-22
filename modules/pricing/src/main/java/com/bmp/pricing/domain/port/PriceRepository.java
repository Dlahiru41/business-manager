package com.bmp.pricing.domain.port;

import com.bmp.pricing.domain.model.Price;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Prices of the current tenant. */
public interface PriceRepository {

    /** Every price of these lists for these packaging units, any validity. */
    List<Price> findByListsAndUnits(Collection<UUID> listIds, Collection<UUID> unitIds);

    /** The prices of these lists and packaging units valid at {@code at}, for resolution. */
    List<Price> findValidAt(Collection<UUID> listIds, Collection<UUID> unitIds, Instant at);

    /** A keyset page of a list's prices ordered by id (creation order). */
    List<Price> findPage(
            UUID listId,
            @Nullable UUID variantId,
            @Nullable UUID unitId,
            @Nullable Instant validAt,
            @Nullable UUID afterId,
            int limit);

    /** Prices changed after a list version (PRC-09 deltas). */
    List<Price> findChangedSince(UUID listId, int version);

    /** Prices of a list not ended at the moment, scheduled ones included (PRC-09 full snapshot). */
    List<Price> findNotEndedAt(UUID listId, Instant at);

    /** Stores new and changed prices and flushes. */
    void saveAll(Collection<Price> prices);
}
