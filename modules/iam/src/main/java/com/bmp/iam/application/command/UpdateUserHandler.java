package com.bmp.iam.application.command;

import com.bmp.iam.application.query.UserView;
import com.bmp.iam.domain.model.AppUser;
import com.bmp.iam.domain.model.RoleId;
import com.bmp.iam.domain.model.UserStatus;
import com.bmp.iam.domain.port.IdentityProviderGateway;
import com.bmp.iam.domain.port.UserRepository;
import com.bmp.shared.id.LocationId;
import com.bmp.shared.id.UserId;
import io.micrometer.observation.annotation.Observed;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Renames, enables/disables or relocates a user. Disabling the last active owner is refused (IAM-01). A status
 * change is made in the identity provider first and undone there if the local transaction fails.
 */
@Service
public class UpdateUserHandler {

    private static final Logger log = LoggerFactory.getLogger(UpdateUserHandler.class);

    private final UserRepository users;
    private final UserLoader loader;
    private final RoleAssignments assignments;
    private final OwnerGuard owners;
    private final IdentityProviderGateway identityProvider;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readOnly;

    public UpdateUserHandler(
            UserRepository users,
            UserLoader loader,
            RoleAssignments assignments,
            OwnerGuard owners,
            IdentityProviderGateway identityProvider,
            PlatformTransactionManager transactionManager) {
        this.users = users;
        this.loader = loader;
        this.assignments = assignments;
        this.owners = owners;
        this.identityProvider = identityProvider;
        this.transaction = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    /**
     * PATCH semantics: {@code null} fields stay unchanged.
     *
     * @param expectedVersion from {@code If-Match}
     */
    public record UpdateUser(
            UUID id,
            long expectedVersion,
            @Nullable String displayName,
            @Nullable UserStatus status,
            @Nullable UUID defaultLocationId) {}

    private record Plan(UserId subject, boolean activeBefore, boolean activeAfter) {
        boolean statusChanges() {
            return activeBefore != activeAfter;
        }
    }

    @Observed(name = "bmp.iam.update-user")
    public UserView handle(UpdateUser command) {
        Plan plan = Objects.requireNonNull(readOnly.execute(status -> {
            AppUser user = loader.load(command.id(), command.expectedVersion());
            UUID location = command.defaultLocationId();
            if (location != null) {
                assignments.requireActiveLocations(List.of(location));
            }
            boolean activeAfter = activeAfter(user, command.status());
            owners.check(owners.ownerRole(false), user, activeAfter, user::holds);
            return new Plan(user.idpSubject(), user.isActive(), activeAfter);
        }));

        if (plan.statusChanges()) {
            identityProvider.setEnabled(plan.subject(), plan.activeAfter());
        }
        UserView updated;
        try {
            updated = Objects.requireNonNull(transaction.execute(status -> apply(command)));
        } catch (RuntimeException e) {
            if (plan.statusChanges()) {
                Compensation.run(
                        "set-enabled",
                        plan.subject().toString(),
                        () -> identityProvider.setEnabled(plan.subject(), plan.activeBefore()));
            }
            throw e;
        }
        log.atInfo()
                .addKeyValue("changedUserId", updated.id().toString())
                .log("User updated userId={} status={}", updated.id(), updated.status());
        return updated;
    }

    private UserView apply(UpdateUser command) {
        Optional<RoleId> ownerRole = owners.ownerRole(true);
        AppUser user = loader.load(command.id(), command.expectedVersion());
        boolean activeAfter = activeAfter(user, command.status());
        owners.check(ownerRole, user, activeAfter, user::holds);
        String displayName = command.displayName();
        if (displayName != null) {
            user.rename(displayName);
        }
        UUID location = command.defaultLocationId();
        if (location != null) {
            user.changeDefaultLocation(LocationId.of(location));
        }
        if (activeAfter && !user.isActive()) {
            user.enable();
        } else if (!activeAfter && user.isActive()) {
            user.disable();
        }
        return loader.view(users.saveAndFlush(user));
    }

    private static boolean activeAfter(AppUser user, @Nullable UserStatus requested) {
        return requested != null ? requested == UserStatus.ACTIVE : user.isActive();
    }
}
