package com.bmp.iam.domain.model;

import com.bmp.shared.id.TypedId;
import java.util.Objects;
import java.util.UUID;

/** Identifies a role of a tenant. */
public record RoleId(UUID value) implements TypedId {

    public RoleId {
        Objects.requireNonNull(value, "value");
    }

    public static RoleId of(UUID value) {
        return new RoleId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
