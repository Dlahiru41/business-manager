package com.bmp.shared.id;

import java.util.Objects;
import java.util.UUID;

/** Identifies a packaging unit of a variant (piece, tray, can, bottle). */
public record PackagingUnitId(UUID value) implements TypedId {

    public PackagingUnitId {
        Objects.requireNonNull(value, "value");
    }

    public static PackagingUnitId of(UUID value) {
        return new PackagingUnitId(value);
    }

    /** Parses a UUID string; throws {@link IllegalArgumentException} when malformed. */
    public static PackagingUnitId of(String value) {
        return new PackagingUnitId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
