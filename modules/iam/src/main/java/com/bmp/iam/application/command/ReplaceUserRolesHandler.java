package com.bmp.iam.application.command;

import com.bmp.iam.application.query.UserView;
import com.bmp.iam.domain.model.AppUser;
import com.bmp.iam.domain.model.RoleId;
import com.bmp.iam.domain.port.IdentityProviderGateway;
import com.bmp.iam.domain.port.UserRepository;
import com.bmp.shared.id.UserId;
import io.micrometer.observation.annotation.Observed;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Replaces the roles of a user. The last active owner keeps the owner role (IAM-01). The identity provider is updated
 * first (the roles travel in its tokens, effective at the next token refresh) and restored if the local change fails.
 */
@Service
public class ReplaceUserRolesHandler {

    private static final Logger log = LoggerFactory.getLogger(ReplaceUserRolesHandler.class);

    private final UserRepository users;
    private final UserLoader loader;
    private final RoleAssignments assignments;
    private final OwnerGuard owners;
    private final GrantCeiling ceiling;
    private final IdentityProviderGateway identityProvider;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readOnly;

    public ReplaceUserRolesHandler(
            UserRepository users,
            UserLoader loader,
            RoleAssignments assignments,
            OwnerGuard owners,
            GrantCeiling ceiling,
            IdentityProviderGateway identityProvider,
            PlatformTransactionManager transactionManager) {
        this.users = users;
        this.loader = loader;
        this.assignments = assignments;
        this.owners = owners;
        this.ceiling = ceiling;
        this.identityProvider = identityProvider;
        this.transaction = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    /**
     * The complete new set of roles.
     *
     * @param expectedVersion from {@code If-Match}
     */
    public record ReplaceUserRoles(UUID id, long expectedVersion, List<RoleRequest> roles) {

        public ReplaceUserRoles {
            roles = List.copyOf(roles);
        }
    }

    private record Plan(UserId subject, List<String> codesBefore, List<String> codesAfter) {}

    @Observed(name = "bmp.iam.replace-user-roles")
    public UserView handle(ReplaceUserRoles command) {
        Plan plan = Objects.requireNonNull(readOnly.execute(status -> {
            AppUser user = loader.load(command.id(), command.expectedVersion());
            RoleAssignments.Resolved resolved = assignments.resolve(command.roles());
            // Neither taking away nor giving more than the caller holds.
            ceiling.requireCovered(loader.permissionsOf(user));
            ceiling.requireCovered(resolved.permissions());
            owners.check(owners.ownerRole(false), user, user.isActive(), holds(resolved));
            return new Plan(user.idpSubject(), loader.roleCodes(user), resolved.codes());
        }));

        identityProvider.setRoles(plan.subject(), plan.codesAfter());
        UserView updated;
        try {
            updated = Objects.requireNonNull(transaction.execute(status -> apply(command)));
        } catch (RuntimeException e) {
            Compensation.run(
                    "set-roles",
                    plan.subject().toString(),
                    () -> identityProvider.setRoles(plan.subject(), plan.codesBefore()));
            throw e;
        }
        log.atInfo()
                .addKeyValue("changedUserId", updated.id().toString())
                .log("User roles replaced userId={} roles={}", updated.id(), plan.codesAfter());
        return updated;
    }

    private UserView apply(ReplaceUserRoles command) {
        Optional<RoleId> ownerRole = owners.ownerRole(true);
        AppUser user = loader.load(command.id(), command.expectedVersion());
        // Resolved again under the lock: a role may have been deactivated since the first check.
        RoleAssignments.Resolved resolved = assignments.resolve(command.roles());
        owners.check(ownerRole, user, user.isActive(), holds(resolved));
        user.replaceRoles(resolved.grants());
        return loader.view(users.saveAndFlush(user));
    }

    private static Predicate<RoleId> holds(RoleAssignments.Resolved resolved) {
        return role ->
                resolved.grants().stream().anyMatch(grant -> grant.roleId().equals(role.value()));
    }
}
