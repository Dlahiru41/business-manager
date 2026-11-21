package com.bmp.iam.application.command;

import com.bmp.iam.application.query.UserView;
import com.bmp.iam.domain.model.AppUser;
import com.bmp.iam.domain.port.IdentityProviderGateway;
import com.bmp.iam.domain.port.IdentityProviderGateway.NewIdentity;
import com.bmp.iam.domain.port.UserRepository;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.LocationId;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import io.micrometer.observation.annotation.Observed;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates a user: validates roles and locations, creates the identity provider account (outside any transaction),
 * then stores the profile. If storing fails, the account is deleted again. No password is accepted or stored.
 */
@Service
public class CreateUserHandler {

    private static final Logger log = LoggerFactory.getLogger(CreateUserHandler.class);

    private final UserRepository users;
    private final RoleAssignments assignments;
    private final IdentityProviderGateway identityProvider;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readOnly;

    public CreateUserHandler(
            UserRepository users,
            RoleAssignments assignments,
            IdentityProviderGateway identityProvider,
            PlatformTransactionManager transactionManager) {
        this.users = users;
        this.assignments = assignments;
        this.identityProvider = identityProvider;
        this.transaction = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    /**
     * A new user.
     *
     * @param email optional; kept by the identity provider only
     */
    public record CreateUser(
            String username,
            @Nullable String email,
            String displayName,
            @Nullable UUID defaultLocationId,
            List<RoleRequest> roles) {

        public CreateUser {
            roles = List.copyOf(roles);
        }

        @Override
        public String toString() {
            return "CreateUser[username=***, email=***, displayName=***, roles=" + roles + "]";
        }
    }

    @Observed(name = "bmp.iam.create-user")
    public UserView handle(CreateUser command) {
        TenantId tenant = TenantContext.require();
        UUID defaultLocation = command.defaultLocationId();
        RoleAssignments.Resolved resolved = Objects.requireNonNull(readOnly.execute(status -> {
            if (defaultLocation != null) {
                assignments.requireActiveLocations(List.of(defaultLocation));
            }
            return assignments.resolve(command.roles());
        }));

        UserId subject = identityProvider.createUser(
                new NewIdentity(command.username(), command.email(), command.displayName(), tenant, resolved.codes()));
        UserView created;
        try {
            created = Objects.requireNonNull(transaction.execute(status -> {
                AppUser user = users.saveAndFlush(AppUser.create(
                        subject,
                        tenant,
                        command.displayName(),
                        defaultLocation != null ? LocationId.of(defaultLocation) : null,
                        resolved.grants()));
                return UserView.of(user, resolved.codesById());
            }));
        } catch (RuntimeException e) {
            Compensation.run("delete-user", subject.toString(), () -> identityProvider.deleteUser(subject));
            throw e;
        }
        log.atInfo()
                .addKeyValue("createdUserId", subject.toString())
                .log("User created userId={} roles={}", subject, resolved.codes());
        return created;
    }
}
