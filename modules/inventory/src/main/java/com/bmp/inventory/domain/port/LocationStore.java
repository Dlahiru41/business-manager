package com.bmp.inventory.domain.port;

import com.bmp.inventory.domain.model.LocationRef;
import java.util.Optional;
import java.util.UUID;

/** The replica of the tenant's locations. */
public interface LocationStore {

    void upsert(LocationRef location);

    Optional<LocationRef> find(UUID locationId);
}
