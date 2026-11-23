package com.bmp.inventory.domain.port;

import com.bmp.inventory.domain.model.BatchInfo;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Batches of variants that track them (INV-14). */
public interface BatchStore {

    /** The batch with this code of the variant, created when new. */
    BatchInfo findOrCreate(
            UUID variantId, String code, @Nullable LocalDate expiresOn, Instant receivedAt, @Nullable UUID supplierId);

    Optional<BatchInfo> find(UUID batchId);

    Map<UUID, BatchInfo> findAll(Collection<UUID> batchIds);
}
