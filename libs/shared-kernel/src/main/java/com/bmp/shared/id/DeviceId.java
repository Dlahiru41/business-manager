package com.bmp.shared.id;

import java.util.Objects;
import java.util.UUID;

/** Identifies a registered device (till). */
public record DeviceId(UUID value) implements TypedId {

    public DeviceId {
        Objects.requireNonNull(value, "value");
    }

    public static DeviceId of(UUID value) {
        return new DeviceId(value);
    }

    /** Parses a UUID string; throws {@link IllegalArgumentException} when malformed. */
    public static DeviceId of(String value) {
        return new DeviceId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
