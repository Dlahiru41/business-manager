package com.bmp.shared.id;

import java.util.Objects;
import java.util.UUID;

/** Identifies a product of the tenant's catalog (Eggs, Cooking oil). */
public record ProductId(UUID value) implements TypedId {

    public ProductId {
        Objects.requireNonNull(value, "value");
    }

    public static ProductId of(UUID value) {
        return new ProductId(value);
    }

    /** Parses a UUID string; throws {@link IllegalArgumentException} when malformed. */
    public static ProductId of(String value) {
        return new ProductId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
