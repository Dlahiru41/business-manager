package com.bmp.shared.id;

import java.util.Objects;
import java.util.UUID;

/** Identifies a location (shop, store room, warehouse) of a tenant. */
public record LocationId(UUID value) implements TypedId {

    public LocationId {
        Objects.requireNonNull(value, "value");
    }

    public static LocationId of(UUID value) {
        return new LocationId(value);
    }

    /** Parses a UUID string; throws {@link IllegalArgumentException} when malformed. */
    public static LocationId of(String value) {
        return new LocationId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
