package com.bmp.iam.application.command;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A role to give a user, by code.
 *
 * @param locationScope locations the role applies to; empty for all
 */
public record RoleRequest(String code, List<UUID> locationScope) {

    public RoleRequest {
        Objects.requireNonNull(code, "code");
        locationScope = List.copyOf(locationScope);
    }
}
