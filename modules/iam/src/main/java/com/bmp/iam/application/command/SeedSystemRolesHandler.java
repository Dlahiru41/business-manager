package com.bmp.iam.application.command;

import com.bmp.iam.application.RolePermissionResolver;
import com.bmp.iam.domain.model.Role;
import com.bmp.iam.domain.model.RoleId;
import com.bmp.iam.domain.port.RoleRepository;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.IdGenerator;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.security.SystemRole;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the system roles with their default permissions into a tenant (on {@code TenantCreated}). Roles that already
 * exist are left as they are, so seeding is safe to repeat.
 */
@Service
public class SeedSystemRolesHandler {

    private static final Logger log = LoggerFactory.getLogger(SeedSystemRolesHandler.class);

    private final RoleRepository roles;
    private final RolePermissionResolver permissions;
    private final IdGenerator ids;

    public SeedSystemRolesHandler(RoleRepository roles, RolePermissionResolver permissions, IdGenerator ids) {
        this.roles = roles;
        this.permissions = permissions;
        this.ids = ids;
    }

    /**
     * Seeds the tenant bound to the current context.
     *
     * @return the number of roles created
     */
    @Transactional
    public int seed() {
        TenantId tenant = TenantContext.require();
        int created = 0;
        for (SystemRole system : Arrays.stream(SystemRole.values())
                .filter(SystemRole::isTenantRole)
                .toList()) {
            if (roles.findByCode(system.code()).isEmpty()) {
                roles.save(Role.seed(ids.next(RoleId::new), tenant, system));
                created++;
            }
        }
        permissions.evictAfterCommit(tenant);
        log.atInfo()
                .addKeyValue("seededTenantId", tenant.toString())
                .log("System roles seeded tenantId={} created={}", tenant, created);
        return created;
    }
}
