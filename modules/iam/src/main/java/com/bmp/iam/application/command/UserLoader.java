package com.bmp.iam.application.command;

import com.bmp.iam.application.query.UserView;
import com.bmp.iam.domain.error.IamErrorCode;
import com.bmp.iam.domain.model.AppUser;
import com.bmp.iam.domain.model.Role;
import com.bmp.iam.domain.model.RoleGrant;
import com.bmp.iam.domain.port.RoleRepository;
import com.bmp.iam.domain.port.UserRepository;
import com.bmp.shared.error.NotFoundException;
import com.bmp.shared.error.VersionConflict;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Loads users for change (404, then the {@code If-Match} version) and builds their read model. */
@Component
class UserLoader {

    private final UserRepository users;
    private final RoleRepository roles;

    UserLoader(UserRepository users, RoleRepository roles) {
        this.users = users;
        this.roles = roles;
    }

    AppUser load(UUID id, long expectedVersion) {
        AppUser user = users.findById(id)
                .orElseThrow(() -> new NotFoundException(IamErrorCode.USER_NOT_FOUND, Map.of("userId", id)));
        if (user.version() != expectedVersion) {
            throw new VersionConflict(user.version());
        }
        return user;
    }

    UserView view(AppUser user) {
        List<UUID> ids = user.roles().stream().map(RoleGrant::roleId).toList();
        Map<UUID, String> codes = ids.isEmpty()
                ? Map.of()
                : roles.findByIdIn(ids).stream()
                        .collect(Collectors.toMap(role -> role.id().value(), Role::code));
        return UserView.of(user, codes);
    }

    /** Every permission grant the user holds now through their roles. */
    Set<String> permissionsOf(AppUser user) {
        List<UUID> ids = user.roles().stream().map(RoleGrant::roleId).toList();
        return ids.isEmpty()
                ? Set.of()
                : roles.findByIdIn(ids).stream()
                        .flatMap(role -> role.effectiveGrants().stream())
                        .collect(Collectors.toSet());
    }

    /** Role codes the user holds now, for the tokens issued by the identity provider. */
    List<String> roleCodes(AppUser user) {
        return view(user).roles().stream().map(grant -> grant.code()).toList();
    }
}
