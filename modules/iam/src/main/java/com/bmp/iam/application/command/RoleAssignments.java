package com.bmp.iam.application.command;

import com.bmp.iam.domain.error.IamErrorCode;
import com.bmp.iam.domain.model.Role;
import com.bmp.iam.domain.model.RoleGrant;
import com.bmp.iam.domain.port.RoleRepository;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.FieldError;
import com.bmp.shared.error.ValidationFailure;
import com.bmp.shared.id.LocationId;
import com.bmp.shared.security.SystemRole;
import com.bmp.tenant.api.TenantQueries;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Turns requested role codes into grants of the current tenant's active roles, and checks the locations they name.
 * Runs inside the caller's transaction.
 */
@Component
class RoleAssignments {

    private final RoleRepository roles;
    private final TenantQueries tenants;

    RoleAssignments(RoleRepository roles, TenantQueries tenants) {
        this.roles = roles;
        this.tenants = tenants;
    }

    /**
     * The resolved grants and the codes to put in the user's tokens, in request order.
     *
     * @param codesById code of each granted role, by role id
     * @param permissions every permission grant the roles give, for the grant ceiling
     */
    record Resolved(List<RoleGrant> grants, List<String> codes, Map<UUID, String> codesById, Set<String> permissions) {}

    Resolved resolve(List<RoleRequest> requested) {
        Set<String> distinct = new HashSet<>();
        for (RoleRequest request : requested) {
            if (!distinct.add(request.code())) {
                throw new ValidationFailure(List.of(new FieldError("roles", "DUPLICATE", "each role may appear once")));
            }
        }
        Map<String, Role> byCode =
                roles.findByCodeIn(distinct).stream().collect(Collectors.toMap(Role::code, Function.identity()));
        List<RoleGrant> grants = requested.stream()
                .map(request -> {
                    Role role = byCode.get(request.code());
                    // The device role belongs to device clients; a person never holds it.
                    if (role == null
                            || !role.isActive()
                            || SystemRole.DEVICE.code().equals(role.code())) {
                        throw new BusinessRuleViolation(IamErrorCode.UNKNOWN_ROLE, Map.of("code", request.code()));
                    }
                    requireActiveLocations(request.locationScope());
                    return new RoleGrant(role.id().value(), request.locationScope());
                })
                .toList();
        Map<UUID, String> codesById = byCode.values().stream()
                .collect(Collectors.toMap(role -> role.id().value(), Role::code));
        Set<String> permissions = byCode.values().stream()
                .flatMap(role -> role.effectiveGrants().stream())
                .collect(Collectors.toSet());
        return new Resolved(grants, requested.stream().map(RoleRequest::code).toList(), codesById, permissions);
    }

    /** Throws {@code INVALID_LOCATION} for the first location that is not active in the tenant. */
    void requireActiveLocations(Collection<UUID> locations) {
        for (UUID location : locations) {
            if (!tenants.isActiveLocation(LocationId.of(location))) {
                throw new BusinessRuleViolation(IamErrorCode.INVALID_LOCATION, Map.of("locationId", location));
            }
        }
    }
}
