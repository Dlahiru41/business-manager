package com.bmp.catalog.domain.port;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Frozen copies of every published definition version (CAT-10), append-only. */
public interface DefinitionVersionLog {

    /**
     * Records a published version.
     *
     * @param snapshotJson the version's content as JSON
     */
    void append(UUID definitionId, int version, Instant publishedAt, @Nullable UUID publishedBy, String snapshotJson);
}
