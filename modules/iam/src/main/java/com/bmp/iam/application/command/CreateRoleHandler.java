package com.bmp.iam.application.command;

import com.bmp.iam.application.RolePermissionResolver;
import com.bmp.iam.application.query.RoleView;
import com.bmp.iam.domain.error.IamErrorCode;
import com.bmp.iam.domain.model.Role;
import com.bmp.iam.domain.model.RoleId;
import com.bmp.iam.domain.port.RoleRepository;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.id.IdGenerator;
import com.bmp.shared.id.TenantId;
import io.micrometer.observation.annotation.Observed;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates a custom role. Its code is unique in the tenant and never a system role code. */
@Service
public class CreateRoleHandler {

    private static final Logger log = LoggerFactory.getLogger(CreateRoleHandler.class);

    private final RoleRepository roles;
    private final RolePermissionResolver permissions;
    private final IdGenerator ids;

    public CreateRoleHandler(RoleRepository roles, RolePermissionResolver permissions, IdGenerator ids) {
        this.roles = roles;
        this.permissions = permissions;
        this.ids = ids;
    }

    /** A new custom role. */
    public record CreateRole(String code, String name, Set<String> permissions) {

        public CreateRole {
            permissions = Set.copyOf(permissions);
        }
    }

    @Transactional
    @Observed(name = "bmp.iam.create-role")
    public RoleView handle(CreateRole command) {
        TenantId tenant = TenantContext.require();
        if (roles.findByCode(command.code()).isPresent()) {
            throw new BusinessRuleViolation(IamErrorCode.ROLE_CODE_TAKEN, Map.of("code", command.code()));
        }
        Role role = roles.saveAndFlush(
                Role.custom(ids.next(RoleId::new), tenant, command.code(), command.name(), command.permissions()));
        permissions.evictAfterCommit(tenant);
        log.atInfo()
                .addKeyValue("roleId", role.id().toString())
                .log("Role created roleId={} code={}", role.id(), role.code());
        return RoleView.of(role);
    }
}
