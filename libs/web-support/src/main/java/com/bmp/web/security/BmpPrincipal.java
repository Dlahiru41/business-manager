package com.bmp.web.security;

import com.bmp.shared.id.DeviceId;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import com.bmp.shared.security.SystemRole;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The authenticated caller, built from the access token (08-security.md §1). The tenant always comes from here,
 * never from the request body or URL.
 *
 * @param userId the identity provider subject (ADR-014)
 * @param tenantId the caller's tenant ({@code tenant_id} claim); absent only for platform administrators
 * @param roles role codes from the token, e.g. {@code CASHIER}
 * @param deviceId the till, for device tokens ({@code device_id} claim)
 */
public record BmpPrincipal(
        UserId userId,
        @Nullable TenantId tenantId,
        Set<String> roles,
        @Nullable DeviceId deviceId) {

    public BmpPrincipal {
        Objects.requireNonNull(userId, "userId");
        roles = Set.copyOf(roles);
    }

    public boolean isPlatformAdmin() {
        return roles.contains(SystemRole.PLATFORM_ADMIN.code());
    }

    /** Role codes that refer to tenant roles (everything except the platform administrator role). */
    public Set<String> tenantRoles() {
        return roles.stream()
                .filter(role -> !role.equals(SystemRole.PLATFORM_ADMIN.code()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
