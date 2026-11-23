package com.bmp.inventory.domain.port;

import com.bmp.inventory.domain.model.CountStatus;
import com.bmp.inventory.domain.model.StockCount;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Stock counts of the current tenant. */
public interface StockCountRepository {

    Optional<StockCount> findById(UUID id);

    /** Newest first, before {@code beforeId}. */
    List<StockCount> findPage(
            @Nullable CountStatus status, @Nullable UUID locationId, @Nullable UUID beforeId, int limit);

    StockCount saveAndFlush(StockCount count);
}
