package com.bmp.iam.application.command;

import com.bmp.iam.application.RolePermissionResolver;
import com.bmp.iam.application.query.RoleView;
import com.bmp.iam.domain.error.IamErrorCode;
import com.bmp.iam.domain.model.Role;
import com.bmp.iam.domain.model.RoleStatus;
import com.bmp.iam.domain.port.RoleRepository;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.NotFoundException;
import com.bmp.shared.error.VersionConflict;
import io.micrometer.observation.annotation.Observed;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Renames a role, replaces its permissions or (custom roles only) changes its status. System roles are never
 * deactivated and the owner role keeps every tenant permission (IAM-02). New permissions apply to tokens at once,
 * because they are resolved per request (after the cache lifetime on other instances).
 */
@Service
public class UpdateRoleHandler {

    private static final Logger log = LoggerFactory.getLogger(UpdateRoleHandler.class);

    private final RoleRepository roles;
    private final RolePermissionResolver permissions;
    private final GrantCeiling ceiling;

    public UpdateRoleHandler(RoleRepository roles, RolePermissionResolver permissions, GrantCeiling ceiling) {
        this.roles = roles;
        this.permissions = permissions;
        this.ceiling = ceiling;
    }

    /**
     * PATCH semantics: {@code null} fields stay unchanged.
     *
     * @param expectedVersion from {@code If-Match}
     */
    public record UpdateRole(
            UUID id,
            long expectedVersion,
            @Nullable String name,
            @Nullable Set<String> permissions,
            @Nullable RoleStatus status) {}

    @Transactional
    @Observed(name = "bmp.iam.update-role")
    public RoleView handle(UpdateRole command) {
        Role role = roles.findById(command.id())
                .orElseThrow(() -> new NotFoundException(IamErrorCode.ROLE_NOT_FOUND, Map.of("roleId", command.id())));
        if (role.version() != command.expectedVersion()) {
            throw new VersionConflict(role.version());
        }
        // A role more powerful than the caller stays out of their reach, and so do the grants they would add
        // (checked after the domain has validated them, so malformed grants stay 422).
        ceiling.requireCovered(role.permissions());
        String name = command.name();
        if (name != null) {
            role.rename(name);
        }
        Set<String> grants = command.permissions();
        if (grants != null) {
            role.changePermissions(grants);
            ceiling.requireCovered(grants);
        }
        RoleStatus status = command.status();
        if (status == RoleStatus.INACTIVE && role.isActive()) {
            role.deactivate();
        } else if (status == RoleStatus.ACTIVE) {
            role.activate();
        }
        Role saved = roles.saveAndFlush(role);
        permissions.evictAfterCommit(TenantContext.require());
        log.atInfo()
                .addKeyValue("roleId", saved.id().toString())
                .log("Role updated roleId={} code={} status={}", saved.id(), saved.code(), saved.status());
        return RoleView.of(saved);
    }
}
