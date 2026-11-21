package com.bmp.iam.application.query;

import com.bmp.iam.domain.model.Role;
import com.bmp.iam.domain.model.RoleStatus;
import java.util.List;
import java.util.UUID;

/** Read model of a role; permissions sorted for stable responses. */
public record RoleView(
        UUID id, String code, String name, boolean system, RoleStatus status, List<String> permissions, long version) {

    public RoleView {
        permissions = List.copyOf(permissions);
    }

    public static RoleView of(Role role) {
        return new RoleView(
                role.id().value(),
                role.code(),
                role.name(),
                role.isSystem(),
                role.status(),
                role.permissions().stream().sorted().toList(),
                role.version());
    }
}
