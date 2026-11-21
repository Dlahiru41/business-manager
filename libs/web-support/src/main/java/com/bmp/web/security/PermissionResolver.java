package com.bmp.web.security;

import com.bmp.shared.id.TenantId;
import com.bmp.shared.security.SystemRole;
import java.util.HashSet;
import java.util.Set;

/**
 * Expands a tenant's role codes into permission grants (08-security.md §3). The mapping is data owned by the
 * {@code iam} module (editable per tenant); this SPI's default uses the documented {@link SystemRole} defaults only.
 */
@FunctionalInterface
public interface PermissionResolver {

    /** Permission grants ({@code PermissionPattern} syntax) of {@code roles} in {@code tenant}. */
    Set<String> grantsFor(TenantId tenant, Set<String> roles);

    /** The documented default role permissions, ignoring tenant customizations. */
    static PermissionResolver systemDefaults() {
        return (tenant, roles) -> {
            Set<String> grants = new HashSet<>();
            roles.forEach(code -> SystemRole.fromCode(code)
                    .filter(SystemRole::isTenantRole)
                    .ifPresent(role -> grants.addAll(role.defaultPermissions())));
            return Set.copyOf(grants);
        };
    }
}
