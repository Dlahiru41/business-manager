package com.bmp.iam.application.command;

import com.bmp.iam.domain.model.AppUser;
import com.bmp.iam.domain.model.Role;
import com.bmp.iam.domain.model.RoleId;
import com.bmp.iam.domain.port.RoleRepository;
import com.bmp.iam.domain.port.UserRepository;
import com.bmp.iam.domain.service.LastOwnerRule;
import com.bmp.shared.security.SystemRole;
import java.util.Optional;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;

/**
 * Applies IAM-01 to a change of one user. In the transaction that makes the change, the owner role is locked first, so
 * concurrent changes that could each remove "the other" owner are serialized and counted correctly.
 */
@Component
class OwnerGuard {

    private final RoleRepository roles;
    private final UserRepository users;

    OwnerGuard(RoleRepository roles, UserRepository users) {
        this.roles = roles;
        this.users = users;
    }

    /** The owner role of the tenant, locked when {@code lock} (inside the writing transaction). Empty before seeding. */
    Optional<RoleId> ownerRole(boolean lock) {
        String code = SystemRole.OWNER.code();
        return (lock ? roles.lockByCode(code) : roles.findByCode(code)).map(Role::id);
    }

    /**
     * Throws {@code LAST_OWNER} when the change would leave no active owner.
     *
     * @param activeAfter whether the user is active after the change
     * @param holdsAfter which roles the user holds after the change
     */
    void check(Optional<RoleId> ownerRole, AppUser user, boolean activeAfter, Predicate<RoleId> holdsAfter) {
        if (ownerRole.isEmpty()) {
            return;
        }
        RoleId owner = ownerRole.get();
        boolean wasActiveOwner = user.isActive() && user.holds(owner);
        if (!wasActiveOwner) {
            return;
        }
        boolean staysActiveOwner = activeAfter && holdsAfter.test(owner);
        LastOwnerRule.check(user.id(), true, staysActiveOwner, users.countActiveHolders(owner.value()));
    }
}
