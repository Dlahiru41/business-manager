package com.bmp.shared.id;

import java.util.Objects;
import java.util.UUID;

/** Identifies a party: a customer, a supplier or both (phase 02). */
public record PartyId(UUID value) implements TypedId {

    public PartyId {
        Objects.requireNonNull(value, "value");
    }

    public static PartyId of(UUID value) {
        return new PartyId(value);
    }

    /** Parses a UUID string; throws {@link IllegalArgumentException} when malformed. */
    public static PartyId of(String value) {
        return new PartyId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
