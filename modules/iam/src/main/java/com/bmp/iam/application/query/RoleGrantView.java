package com.bmp.iam.application.query;

import java.util.List;
import java.util.UUID;

/**
 * A role held by a user.
 *
 * @param locationScope locations the role applies to; empty for all
 */
public record RoleGrantView(String code, List<UUID> locationScope) {

    public RoleGrantView {
        locationScope = List.copyOf(locationScope);
    }
}
