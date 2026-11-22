package com.bmp.pricing.domain.port;

import com.bmp.pricing.domain.model.VariantRef;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** The replica of catalog variants (T01-05); newer snapshots win, older ones are ignored. */
public interface VariantRefStore {

    /** Stores the snapshot unless a newer one (higher aggregate version) is already stored. */
    void upsert(VariantRef ref);

    /** Marks the variant inactive at this version, leaving a tombstone when its creation has not arrived yet. */
    void deactivate(UUID variantId, UUID productId, long aggregateVersion);

    Map<UUID, VariantRef> findAll(Collection<UUID> variantIds);
}
