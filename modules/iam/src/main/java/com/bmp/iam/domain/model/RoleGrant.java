package com.bmp.iam.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A role held by a user, optionally limited to some locations (an empty scope means every location). Location scope
 * is enforced by the application handlers of the modules that act on a location.
 *
 * @param roleId the role
 * @param locationScope locations the role applies to; empty for all
 */
@Embeddable
public record RoleGrant(
        @Column(name = "role_id", nullable = false) UUID roleId,

        @Column(name = "location_scope", nullable = false) @JdbcTypeCode(SqlTypes.ARRAY)
        List<UUID> locationScope) {

    public RoleGrant {
        Objects.requireNonNull(roleId, "roleId");
        locationScope = locationScope == null ? List.of() : List.copyOf(locationScope);
    }

    public static RoleGrant everywhere(RoleId role) {
        return new RoleGrant(role.value(), List.of());
    }
}
