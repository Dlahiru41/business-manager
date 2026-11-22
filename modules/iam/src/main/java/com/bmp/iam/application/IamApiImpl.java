package com.bmp.iam.application;

import com.bmp.iam.api.IamProvisioning;
import com.bmp.iam.api.dto.ExistingAccount;
import com.bmp.iam.application.command.SeedSystemRolesHandler;
import com.bmp.iam.domain.error.IamErrorCode;
import com.bmp.iam.domain.model.AppUser;
import com.bmp.iam.domain.model.Role;
import com.bmp.iam.domain.model.RoleGrant;
import com.bmp.iam.domain.port.RoleRepository;
import com.bmp.iam.domain.port.UserRepository;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.id.TenantId;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Implements the public API of the iam module for other modules. */
@Component
class IamApiImpl implements IamProvisioning {

    private static final Logger log = LoggerFactory.getLogger(IamApiImpl.class);

    private final UserRepository users;
    private final RoleRepository roles;
    private final SeedSystemRolesHandler seeder;
    private final TransactionTemplate transaction;

    IamApiImpl(
            UserRepository users,
            RoleRepository roles,
            SeedSystemRolesHandler seeder,
            PlatformTransactionManager transactionManager) {
        this.users = users;
        this.roles = roles;
        this.seeder = seeder;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public void provision(TenantId tenant, ExistingAccount account) {
        TenantContext.runWith(
                tenant,
                () -> transaction.executeWithoutResult(status -> {
                    seeder.seed();
                    if (users.findById(account.subject().value()).isPresent()) {
                        return;
                    }
                    List<RoleGrant> grants = account.roleCodes().stream()
                            .map(code -> roles.findByCode(code)
                                    .filter(Role::isActive)
                                    .map(role -> RoleGrant.everywhere(role.id()))
                                    .orElseThrow(() ->
                                            new BusinessRuleViolation(IamErrorCode.UNKNOWN_ROLE, Map.of("code", code))))
                            .toList();
                    users.save(AppUser.create(account.subject(), tenant, account.displayName(), null, grants));
                    log.atInfo()
                            .addKeyValue("provisionedUserId", account.subject().toString())
                            .log("User provisioned userId={} roles={}", account.subject(), account.roleCodes());
                }));
    }
}
