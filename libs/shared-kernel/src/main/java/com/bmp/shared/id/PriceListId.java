package com.bmp.shared.id;

import java.util.Objects;
import java.util.UUID;

/** Identifies a price list (Retail, Wholesale, customer-specific). */
public record PriceListId(UUID value) implements TypedId {

    public PriceListId {
        Objects.requireNonNull(value, "value");
    }

    public static PriceListId of(UUID value) {
        return new PriceListId(value);
    }

    /** Parses a UUID string; throws {@link IllegalArgumentException} when malformed. */
    public static PriceListId of(String value) {
        return new PriceListId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
