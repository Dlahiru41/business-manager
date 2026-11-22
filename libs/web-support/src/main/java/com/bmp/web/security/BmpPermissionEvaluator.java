package com.bmp.web.security;

import java.io.Serializable;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;

/**
 * Resolves {@code hasPermission(null, 'area:action')} in {@code @PreAuthorize}: permissions, not roles, in code
 * (08-security.md §3). Object-level rules live in application handlers.
 */
public class BmpPermissionEvaluator implements PermissionEvaluator {

    private final Supplier<AccessPolicy> access;

    /** @param access supplies the policy lazily, so the evaluator can be created before application beans */
    public BmpPermissionEvaluator(Supplier<AccessPolicy> access) {
        this.access = access;
    }

    @Override
    public boolean hasPermission(Authentication authentication, @Nullable Object target, Object permission) {
        return authentication instanceof BmpAuthentication bmp
                && permission instanceof String name
                && access.get().isGranted(bmp.bmpPrincipal(), name);
    }

    @Override
    public boolean hasPermission(
            Authentication authentication, Serializable targetId, String targetType, Object permission) {
        return hasPermission(authentication, null, permission);
    }
}
