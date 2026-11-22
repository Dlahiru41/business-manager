package com.bmp.iam.application.command;

import com.bmp.iam.domain.error.IamErrorCode;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.shared.security.PermissionPattern;
import com.bmp.web.security.AccessPolicy;
import com.bmp.web.security.BmpPrincipal;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * No privilege escalation through role management (OWASP API5, ASVS V4.2): a caller may grant, create, change or
 * take away only what their own grants cover. Holding {@code iam:users} therefore lets a manager give a cashier the
 * cashier role, but never the owner role; only a holder of {@code *} (an owner, or a platform administrator acting
 * for the tenant) can manage owners.
 */
@Component
class GrantCeiling {

    private final AccessPolicy access;

    GrantCeiling(AccessPolicy access) {
        this.access = access;
    }

    /** Throws {@code ROLE_GRANT_EXCEEDS_CALLER} unless the caller holds every grant. */
    void requireCovered(Collection<String> grants) {
        BmpPrincipal caller = AccessPolicy.currentPrincipal()
                .orElseThrow(() -> new BusinessRuleViolation(PlatformErrorCode.UNAUTHENTICATED));
        Set<String> held = access.grantsOf(caller);
        for (String grant : grants) {
            if (!PermissionPattern.coveredBy(held, grant)) {
                throw new BusinessRuleViolation(IamErrorCode.ROLE_GRANT_EXCEEDS_CALLER, Map.of("permission", grant));
            }
        }
    }
}
