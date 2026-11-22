package com.bmp.shared.id;

import java.util.Objects;
import java.util.UUID;

/** Identifies a variant (SKU) — the thing stock, cost and price attach to. */
public record VariantId(UUID value) implements TypedId {

    public VariantId {
        Objects.requireNonNull(value, "value");
    }

    public static VariantId of(UUID value) {
        return new VariantId(value);
    }

    /** Parses a UUID string; throws {@link IllegalArgumentException} when malformed. */
    public static VariantId of(String value) {
        return new VariantId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
