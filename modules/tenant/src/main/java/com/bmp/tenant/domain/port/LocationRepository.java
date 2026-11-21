package com.bmp.tenant.domain.port;

import com.bmp.tenant.domain.model.Location;
import com.bmp.tenant.domain.model.LocationStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Locations of the current tenant. */
public interface LocationRepository {

    Optional<Location> findById(UUID id);

    Location save(Location location);

    /** Saves and flushes, so the returned entity carries its new version. */
    Location saveAndFlush(Location location);

    long countByStatus(LocationStatus status);

    /** Keyset page ordered by creation time then id, starting after the given cursor (or from the start). */
    List<Location> findPage(Instant afterCreatedAt, UUID afterId, int limit);
}
