package com.bmp.iam.application.command;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bmp.iam.domain.model.AppUser;
import com.bmp.iam.domain.model.Device;
import com.bmp.iam.domain.model.RoleGrant;
import com.bmp.iam.domain.model.RoleId;
import com.bmp.iam.domain.model.UserStatus;
import com.bmp.iam.domain.port.DeviceRepository;
import com.bmp.iam.domain.port.IdentityProviderGateway;
import com.bmp.iam.domain.port.IdentityProviderGateway.DeviceCredential;
import com.bmp.iam.domain.port.UserRepository;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.VersionConflict;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.event.EventStamp;
import com.bmp.shared.event.EventStampSource;
import com.bmp.shared.id.DeviceId;
import com.bmp.shared.id.IdGenerator;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * When the local transaction fails after the identity provider changed, the change is undone there; a failing undo
 * never hides the original error.
 */
class CompensationTest {

    private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
    private static final UserId SUBJECT = UserId.of(UUID.randomUUID());
    private static final RoleId CASHIER = RoleId.of(UUID.randomUUID());

    private final UserRepository users = mock(UserRepository.class);
    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final RoleAssignments assignments = mock(RoleAssignments.class);
    private final OwnerGuard owners = mock(OwnerGuard.class);
    private final UserLoader loader = mock(UserLoader.class);
    private final GrantCeiling ceiling = mock(GrantCeiling.class);
    private final IdentityProviderGateway identityProvider = mock(IdentityProviderGateway.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final RoleAssignments.Resolved cashier = new RoleAssignments.Resolved(
            List.of(RoleGrant.everywhere(CASHIER)),
            List.of("CASHIER"),
            Map.of(CASHIER.value(), "CASHIER"),
            Set.of("sales:create"));

    @BeforeEach
    void setUp() {
        when(transactions.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        when(assignments.resolve(anyList())).thenReturn(cashier);
        when(owners.ownerRole(anyBoolean())).thenReturn(Optional.empty());
    }

    private static AppUser user() {
        return AppUser.create(SUBJECT, TENANT, "Nimal", null, List.of(RoleGrant.everywhere(CASHIER)));
    }

    private static void inTenant(Runnable action) {
        TenantContext.runWith(TENANT, action);
    }

    @Test
    void deletesTheAccountWhenTheProfileCannotBeStored() {
        when(identityProvider.createUser(any())).thenReturn(SUBJECT);
        when(users.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));
        CreateUserHandler handler = new CreateUserHandler(users, assignments, ceiling, identityProvider, transactions);

        inTenant(() -> assertThatThrownBy(() -> handler.handle(new CreateUserHandler.CreateUser(
                        "nimal", null, "Nimal", null, List.of(new RoleRequest("CASHIER", List.of())))))
                .isInstanceOf(DataIntegrityViolationException.class));

        verify(identityProvider).deleteUser(SUBJECT);
    }

    @Test
    void aFailingUndoNeverHidesTheOriginalError() {
        when(identityProvider.createUser(any())).thenReturn(SUBJECT);
        when(users.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));
        doThrow(new IllegalStateException("identity provider down"))
                .when(identityProvider)
                .deleteUser(SUBJECT);
        CreateUserHandler handler = new CreateUserHandler(users, assignments, ceiling, identityProvider, transactions);

        inTenant(() -> assertThatThrownBy(() -> handler.handle(new CreateUserHandler.CreateUser(
                        "nimal", "n@example.lk", "Nimal", null, List.of(new RoleRequest("CASHIER", List.of())))))
                .isInstanceOf(DataIntegrityViolationException.class));
    }

    @Test
    void reEnablesTheAccountWhenDisablingFailsLocally() {
        when(loader.load(any(), eq(0L))).thenReturn(user()).thenThrow(new VersionConflict(1));
        UpdateUserHandler handler =
                new UpdateUserHandler(users, loader, assignments, owners, ceiling, identityProvider, transactions);

        inTenant(() -> assertThatThrownBy(() -> handler.handle(
                        new UpdateUserHandler.UpdateUser(SUBJECT.value(), 0, null, UserStatus.DISABLED, null)))
                .isInstanceOf(VersionConflict.class));

        verify(identityProvider).setEnabled(SUBJECT, false);
        verify(identityProvider).setEnabled(SUBJECT, true);
    }

    @Test
    void restoresTheOldRolesWhenReplacingFailsLocally() {
        when(loader.load(any(), eq(0L))).thenReturn(user()).thenThrow(new VersionConflict(1));
        when(loader.roleCodes(any())).thenReturn(List.of("CASHIER"));
        when(assignments.resolve(anyList()))
                .thenReturn(new RoleAssignments.Resolved(List.of(), List.of("MANAGER"), Map.of(), Set.of()));
        ReplaceUserRolesHandler handler = new ReplaceUserRolesHandler(
                users, loader, assignments, owners, ceiling, identityProvider, transactions);

        inTenant(() -> assertThatThrownBy(() -> handler.handle(new ReplaceUserRolesHandler.ReplaceUserRoles(
                        SUBJECT.value(), 0, List.of(new RoleRequest("MANAGER", List.of())))))
                .isInstanceOf(VersionConflict.class));

        verify(identityProvider).setRoles(SUBJECT, List.of("MANAGER"));
        verify(identityProvider).setRoles(SUBJECT, List.of("CASHIER"));
    }

    @Test
    void deletesTheDeviceClientWhenTheDeviceCannotBeStored() {
        DeviceId device = DeviceId.of(UUID.randomUUID());
        IdGenerator ids = mock(IdGenerator.class);
        when(ids.next(any())).thenReturn(device);
        when(identityProvider.registerDevice(any()))
                .thenReturn(new DeviceCredential(Device.clientIdOf(device), "secret"));
        when(devices.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));
        EventStampSource stamps = () -> new EventStamp(
                UUID.randomUUID(), Instant.parse("2026-11-21T10:00:00Z"), new EventMetadata(null, null, null, null));
        RegisterDeviceHandler handler = new RegisterDeviceHandler(
                devices,
                assignments,
                identityProvider,
                mock(ApplicationEventPublisher.class),
                stamps,
                ids,
                transactions);

        inTenant(() -> assertThatThrownBy(
                        () -> handler.handle(new RegisterDeviceHandler.RegisterDevice("Till", UUID.randomUUID(), null)))
                .isInstanceOf(DataIntegrityViolationException.class));

        verify(identityProvider).deleteDevice(Device.clientIdOf(device));
    }
}
