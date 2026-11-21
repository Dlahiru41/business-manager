package com.bmp.shared.id;

import java.util.Objects;
import java.util.UUID;

/** Identifies a human user; the value is the identity provider's subject id (see ADR-014). */
public record UserId(UUID value) implements TypedId {

    public UserId {
        Objects.requireNonNull(value, "value");
    }

    public static UserId of(UUID value) {
        return new UserId(value);
    }

    /** Parses a UUID string; throws {@link IllegalArgumentException} when malformed. */
    public static UserId of(String value) {
        return new UserId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
