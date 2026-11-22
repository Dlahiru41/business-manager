package com.bmp.shared.id;

import java.util.Objects;
import java.util.UUID;

/** Identifies a tenant (one business using the platform). */
public record TenantId(UUID value) implements TypedId {

    public TenantId {
        Objects.requireNonNull(value, "value");
    }

    public static TenantId of(UUID value) {
        return new TenantId(value);
    }

    /** Parses a UUID string; throws {@link IllegalArgumentException} when malformed. */
    public static TenantId of(String value) {
        return new TenantId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
