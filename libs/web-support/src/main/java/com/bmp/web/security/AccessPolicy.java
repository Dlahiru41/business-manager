package com.bmp.web.security;

import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.shared.security.PermissionPattern;
import com.bmp.shared.security.SystemRole;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Permission decisions for the current caller, used by {@code @PreAuthorize("hasPermission(null, '...')")} through
 * {@link BmpPermissionEvaluator} and by application handlers for rules that depend on the caller (e.g. TEN-02).
 *
 * <p>A platform administrator holds {@code platform:*}; tenant permissions only while acting for a tenant
 * ({@code X-Tenant-Id}), so a tenant endpoint never runs without a tenant.
 */
public class AccessPolicy {

    private final PermissionResolver resolver;

    public AccessPolicy(PermissionResolver resolver) {
        this.resolver = resolver;
    }

    /** All grants of the principal in the current tenant context. */
    public Set<String> grantsOf(BmpPrincipal principal) {
        Set<String> grants = new HashSet<>();
        TenantContext.current().ifPresent(tenant -> grants.addAll(resolver.grantsFor(tenant, principal.tenantRoles())));
        if (principal.isPlatformAdmin()) {
            grants.add("platform:*");
            if (TenantContext.current().isPresent()) {
                grants.addAll(SystemRole.PLATFORM_ADMIN.defaultPermissions());
            }
        }
        return grants;
    }

    public boolean isGranted(BmpPrincipal principal, String permission) {
        return PermissionPattern.anyMatches(grantsOf(principal), permission);
    }

    /** Whether the current caller holds {@code permission}. */
    public boolean isGranted(String permission) {
        return currentPrincipal().map(p -> isGranted(p, permission)).orElse(false);
    }

    /** Throws {@code ACCESS_DENIED} unless the current caller holds {@code permission}. */
    public void require(String permission) {
        if (!isGranted(permission)) {
            throw new BusinessRuleViolation(PlatformErrorCode.ACCESS_DENIED, Map.of("permission", permission));
        }
    }

    public static Optional<BmpPrincipal> currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication instanceof BmpAuthentication bmp ? Optional.of(bmp.bmpPrincipal()) : Optional.empty();
    }
}
