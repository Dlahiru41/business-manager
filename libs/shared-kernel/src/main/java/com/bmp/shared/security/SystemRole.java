package com.bmp.shared.security;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * Built-in roles and their default permission patterns (08-security.md §3). Tenant roles are seeded per tenant by the
 * {@code iam} module, which stores the editable mapping; {@link #PLATFORM_ADMIN} is a platform role carried by the
 * identity provider and never stored per tenant. Patterns use {@link PermissionPattern} syntax.
 */
@SuppressWarnings("ImmutableEnumChecker") // permission sets are created with Set.of and are immutable
public enum SystemRole {
    OWNER(true, Set.of(PermissionPattern.ALL_TENANT_PERMISSIONS)),
    MANAGER(
            true,
            Set.of(
                    "catalog:*",
                    "pricing:write",
                    "inventory:*",
                    "procurement:*",
                    "sales:*",
                    "payments:*",
                    "reports:read")),
    CASHIER(
            true,
            Set.of(
                    "sales:create",
                    "sales:discount",
                    "payments:capture",
                    "drawer:operate-own",
                    "catalog:read",
                    "pricing:read",
                    "party:read",
                    "party:create-customer")),
    STOREKEEPER(
            true,
            Set.of(
                    "inventory:receive",
                    "inventory:transform",
                    "inventory:adjust",
                    "inventory:count",
                    "procurement:receive",
                    "catalog:read")),
    ACCOUNTANT(true, Set.of("finance:*", "reports:*", "payments:read", "sales:read")),
    DEVICE(true, Set.of("sync:*", "documents:print")),
    /** Operates the platform; may act for a tenant via {@code X-Tenant-Id} (audited). */
    PLATFORM_ADMIN(false, Set.of("platform:*", PermissionPattern.ALL_TENANT_PERMISSIONS));

    private final boolean tenantRole;
    private final Set<String> defaultPermissions;

    SystemRole(boolean tenantRole, Set<String> defaultPermissions) {
        this.tenantRole = tenantRole;
        this.defaultPermissions = defaultPermissions;
    }

    /** The role code as it appears in tokens and in the {@code iam.role} table. */
    public String code() {
        return this == PLATFORM_ADMIN ? "platform_admin" : name();
    }

    /** Whether the role is seeded into every tenant (all except {@link #PLATFORM_ADMIN}). */
    public boolean isTenantRole() {
        return tenantRole;
    }

    public Set<String> defaultPermissions() {
        return defaultPermissions;
    }

    public static Optional<SystemRole> fromCode(String code) {
        return Arrays.stream(values()).filter(r -> r.code().equals(code)).findFirst();
    }
}
