package com.bmp.shared.id;

import java.util.Objects;
import java.util.UUID;

/** Identifies a billing counter (register) at a location. */
public record RegisterId(UUID value) implements TypedId {

    public RegisterId {
        Objects.requireNonNull(value, "value");
    }

    public static RegisterId of(UUID value) {
        return new RegisterId(value);
    }

    /** Parses a UUID string; throws {@link IllegalArgumentException} when malformed. */
    public static RegisterId of(String value) {
        return new RegisterId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
