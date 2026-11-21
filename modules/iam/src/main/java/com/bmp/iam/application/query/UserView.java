package com.bmp.iam.application.query;

import com.bmp.iam.domain.model.AppUser;
import com.bmp.iam.domain.model.RoleGrant;
import com.bmp.iam.domain.model.UserStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Read model of a user; no personal data beyond the display name (username and e-mail stay in the IdP). */
public record UserView(
        UUID id,
        UUID idpSubject,
        String displayName,
        UserStatus status,
        @Nullable UUID defaultLocationId,
        List<RoleGrantView> roles,
        long version,
        Instant createdAt) {

    public UserView {
        roles = List.copyOf(roles);
    }

    /** Builds the view; {@code roleCodes} maps the id of every role the user holds to its code. */
    public static UserView of(AppUser user, Map<UUID, String> roleCodes) {
        List<RoleGrantView> roles = user.roles().stream()
                .map(grant -> new RoleGrantView(codeOf(grant, roleCodes), grant.locationScope()))
                .toList();
        return new UserView(
                user.id().value(),
                user.idpSubject().value(),
                user.displayName(),
                user.status(),
                user.defaultLocationId(),
                roles,
                user.version(),
                user.createdAt());
    }

    private static String codeOf(RoleGrant grant, Map<UUID, String> roleCodes) {
        String code = roleCodes.get(grant.roleId());
        if (code == null) {
            throw new IllegalStateException("Role " + grant.roleId() + " of a user is missing");
        }
        return code;
    }
}
