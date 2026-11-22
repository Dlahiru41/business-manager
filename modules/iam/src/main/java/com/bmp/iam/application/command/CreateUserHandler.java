package com.bmp.iam.application.command;

import com.bmp.iam.application.query.UserView;
import com.bmp.iam.domain.error.IamErrorCode;
import com.bmp.iam.domain.model.AppUser;
import com.bmp.iam.domain.port.IdentityProviderGateway;
import com.bmp.iam.domain.port.IdentityProviderGateway.ExistingIdentity;
import com.bmp.iam.domain.port.IdentityProviderGateway.NewIdentity;
import com.bmp.iam.domain.port.UserRepository;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.BmpException;
import com.bmp.shared.id.LocationId;
import com.bmp.shared.id.TenantId;
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
 * Creates a user without ever leaving a usable account behind that has no profile:
 *
 * <ol>
 *   <li>validates roles (within the caller's own grants), locations and the request;
 *   <li>creates the identity provider account <em>disabled</em>, outside any transaction; if the username exists but
 *       is an account of this tenant without a profile (a creation whose answer was lost), that account is adopted;
 *   <li>stores the profile (on failure the account is deleted again);
 *   <li>enables the account and sends the invitation. If that fails, the profile is disabled too, so the two stay
 *       consistent; an administrator enables the user later.
 * </ol>
 *
 * <p>No password is accepted or stored.
 */
@Service
public class CreateUserHandler {

    private static final Logger log = LoggerFactory.getLogger(CreateUserHandler.class);

    private final UserRepository users;
    private final RoleAssignments assignments;
    private final GrantCeiling ceiling;
    private final IdentityProviderGateway identityProvider;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readOnly;

    public CreateUserHandler(
            UserRepository users,
            RoleAssignments assignments,
            GrantCeiling ceiling,
            IdentityProviderGateway identityProvider,
            PlatformTransactionManager transactionManager) {
        this.users = users;
        this.assignments = assignments;
        this.ceiling = ceiling;
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
        ceiling.requireCovered(resolved.permissions());

        UserId subject = createOrAdopt(command, tenant, resolved.codes());
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
        activate(subject, command.email() != null);
        log.atInfo()
                .addKeyValue("createdUserId", subject.toString())
                .log("User created userId={} roles={}", subject, resolved.codes());
        return created;
    }

    private UserId createOrAdopt(CreateUser command, TenantId tenant, List<String> roleCodes) {
        try {
            return identityProvider.createUser(
                    new NewIdentity(command.username(), command.email(), command.displayName(), tenant, roleCodes));
        } catch (BmpException e) {
            if (e.errorCode() != IamErrorCode.USERNAME_TAKEN) {
                throw e;
            }
            UserId orphan = orphanOf(command.username(), tenant).orElseThrow(() -> e);
            identityProvider.setRoles(orphan, roleCodes);
            log.atWarn()
                    .addKeyValue("adoptedUserId", orphan.toString())
                    .log("Adopted an account of this tenant that had no profile userId={}", orphan);
            return orphan;
        }
    }

    /** An existing account of this tenant without a profile: left behind by a creation whose answer was lost. */
    private Optional<UserId> orphanOf(String username, TenantId tenant) {
        Optional<ExistingIdentity> account = identityProvider.findUser(username);
        if (account.isEmpty() || !tenant.toString().equals(account.get().tenant())) {
            return Optional.empty();
        }
        UserId subject = account.get().subject();
        boolean hasProfile = Boolean.TRUE.equals(
                readOnly.execute(status -> users.findById(subject.value()).isPresent()));
        return hasProfile ? Optional.empty() : Optional.of(subject);
    }

    private void activate(UserId subject, boolean invite) {
        try {
            identityProvider.activateUser(subject, invite);
        } catch (RuntimeException e) {
            Compensation.run(
                    "disable-profile",
                    subject.toString(),
                    () -> transaction.executeWithoutResult(status -> {
                        AppUser user = users.findById(subject.value()).orElseThrow();
                        user.disable();
                        users.saveAndFlush(user);
                    }));
            throw e;
        }
    }
}
