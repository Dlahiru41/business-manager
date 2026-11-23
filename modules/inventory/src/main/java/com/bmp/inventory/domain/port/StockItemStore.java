package com.bmp.inventory.domain.port;

import com.bmp.inventory.domain.model.StockItem;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The replica of catalog variants and products; writes keep the newest aggregate version (07 §4). */
public interface StockItemStore {

    void upsert(StockItem item);

    /** Marks the variant inactive; leaves a tombstone when the deactivation arrives first. */
    void deactivate(UUID variantId, UUID productId, String sku, long aggregateVersion);

    void upsertProduct(UUID productId, String name, @Nullable UUID categoryId, long aggregateVersion);

    Optional<StockItem> find(UUID variantId);

    Map<UUID, StockItem> findAll(Collection<UUID> variantIds);
}
