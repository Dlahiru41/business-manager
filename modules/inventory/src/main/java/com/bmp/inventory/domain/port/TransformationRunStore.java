package com.bmp.inventory.domain.port;

import com.bmp.inventory.domain.model.TransformationRun;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Transformation runs; insert-only. */
public interface TransformationRunStore {

    void insert(TransformationRun run);

    Optional<TransformationRun> find(UUID id);

    /** Newest first (ids are UUIDv7), before {@code beforeId}. */
    List<TransformationRun> findPage(
            @Nullable UUID definitionId, @Nullable UUID locationId, @Nullable UUID beforeId, int limit);
}
