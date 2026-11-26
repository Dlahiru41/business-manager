package com.bmp.iam.application;

import com.bmp.iam.api.IamQueries;
import com.bmp.iam.api.dto.DeviceBinding;
import com.bmp.iam.domain.model.AppUser;
import com.bmp.iam.domain.model.Role;
import com.bmp.iam.domain.model.RoleGrant;
import com.bmp.iam.domain.port.DeviceRepository;
import com.bmp.iam.domain.port.RoleRepository;
import com.bmp.iam.domain.port.UserRepository;
import com.bmp.shared.id.DeviceId;
import com.bmp.shared.id.UserId;
import com.bmp.shared.security.PermissionPattern;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** {@link IamQueries} over the tenant's users, roles and devices. */
@Component
class IamQueriesImpl implements IamQueries {

    private final UserRepository users;
    private final RoleRepository roles;
    private final DeviceRepository devices;

    IamQueriesImpl(UserRepository users, RoleRepository roles, DeviceRepository devices) {
        this.users = users;
        this.roles = roles;
        this.devices = devices;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean userHasPermission(UserId user, String permission) {
        return users.findById(user.value())
                .filter(AppUser::isActive)
                .map(u -> roles
                        .findByIdIn(u.roles().stream().map(RoleGrant::roleId).toList())
                        .stream()
                        .filter(Role::isActive)
                        .flatMap(r -> r.effectiveGrants().stream())
                        .anyMatch(grant -> PermissionPattern.matches(grant, permission)))
                .orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DeviceBinding> findDevice(DeviceId device) {
        return devices.findById(device.value())
                .map(d -> new DeviceBinding(d.id(), d.registerId(), d.locationId(), d.isActive()));
    }
}
