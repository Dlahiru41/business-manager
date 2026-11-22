package com.bmp.tenant.application.query;

import com.bmp.tenant.domain.model.Location;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Read model of a location; the address is a JSON document. */
public record LocationView(
        UUID id, String name, String type, @Nullable String address, String status, long version, Instant createdAt) {

    public static LocationView of(Location location) {
        return new LocationView(
                location.id().value(),
                location.name(),
                location.type().name(),
                location.address(),
                location.status().name(),
                location.version(),
                location.createdAt());
    }
}
