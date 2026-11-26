package com.bmp.iam.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bmp.iam.api.dto.DeviceBinding;
import com.bmp.iam.domain.model.AppUser;
import com.bmp.iam.domain.model.Device;
import com.bmp.iam.domain.model.Role;
import com.bmp.iam.domain.model.RoleGrant;
import com.bmp.iam.domain.model.RoleId;
import com.bmp.iam.domain.port.DeviceRepository;
import com.bmp.iam.domain.port.RoleRepository;
import com.bmp.iam.domain.port.UserRepository;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.event.EventStamp;
import com.bmp.shared.id.DeviceId;
import com.bmp.shared.id.LocationId;
import com.bmp.shared.id.RegisterId;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IamQueriesImplTest {

    private static final TenantId TENANT = TenantId.of(UUID.randomUUID());

    private final UserRepository users = mock(UserRepository.class);
    private final RoleRepository roles = mock(RoleRepository.class);
    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final IamQueriesImpl queries = new IamQueriesImpl(users, roles, devices);

    private static Role role(String code, Set<String> grants) {
        return Role.custom(RoleId.of(UUID.randomUUID()), TENANT, code, code, grants);
    }

    private AppUser user(Role... held) {
        UserId id = UserId.of(UUID.randomUUID());
        AppUser user = AppUser.create(
                id,
                TENANT,
                "Kamala",
                null,
                List.of(held).stream().map(r -> RoleGrant.everywhere(r.id())).toList());
        when(users.findById(id.value())).thenReturn(Optional.of(user));
        return user;
    }

    @Test
    void aUserHoldsAPermissionThroughAnActiveRoleGrantOrWildcard() {
        Role manager = role("SUPERVISOR", Set.of("sales:*"));
        Role packer = role("PACKER", Set.of("inventory:count"));
        packer.deactivate();
        when(roles.findByIdIn(anyCollection())).thenReturn(List.of(manager, packer));
        UserId kamala = user(manager, packer).id();

        assertThat(queries.userHasPermission(kamala, "sales:void")).isTrue();
        assertThat(queries.userHasPermission(kamala, "inventory:count"))
                .as("the role granting it is inactive")
                .isFalse();
        assertThat(queries.userHasPermission(kamala, "finance:post")).isFalse();
    }

    @Test
    void unknownOrDisabledUsersHoldNothing() {
        Role manager = role("SUPERVISOR", Set.of("sales:*"));
        when(roles.findByIdIn(anyCollection())).thenReturn(List.of(manager));
        when(users.findById(any())).thenAnswer(invocation -> Optional.empty());
        AppUser disabled = user(manager);
        assertThat(queries.userHasPermission(disabled.id(), "sales:void")).isTrue();

        disabled.disable();

        assertThat(queries.userHasPermission(disabled.id(), "sales:void")).isFalse();
        assertThat(queries.userHasPermission(UserId.of(UUID.randomUUID()), "sales:void"))
                .isFalse();
    }

    @Test
    void aDeviceIsFoundWithItsRegisterAndLocation() {
        DeviceId till = DeviceId.of(UUID.randomUUID());
        LocationId shop = LocationId.of(UUID.randomUUID());
        RegisterId counter = RegisterId.of(UUID.randomUUID());
        Device device = Device.register(
                till,
                TENANT,
                "Till",
                shop,
                counter,
                () -> new EventStamp(UUID.randomUUID(), Instant.EPOCH, EventMetadata.empty()));
        when(devices.findById(any())).thenReturn(Optional.empty());
        when(devices.findById(till.value())).thenReturn(Optional.of(device));

        assertThat(queries.findDevice(till)).contains(new DeviceBinding(till, counter.value(), shop.value(), true));
        assertThat(queries.findDevice(DeviceId.of(UUID.randomUUID()))).isEmpty();
    }
}
