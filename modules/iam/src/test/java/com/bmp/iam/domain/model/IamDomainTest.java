package com.bmp.iam.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.iam.api.events.DeviceRegistered;
import com.bmp.iam.domain.service.LastOwnerRule;
import com.bmp.shared.error.BmpException;
import com.bmp.shared.error.ValidationFailure;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.event.EventStamp;
import com.bmp.shared.event.EventStampSource;
import com.bmp.shared.id.DeviceId;
import com.bmp.shared.id.LocationId;
import com.bmp.shared.id.RegisterId;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import com.bmp.shared.security.SystemRole;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class IamDomainTest {

    private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");
    private static final EventStampSource STAMPS =
            () -> new EventStamp(UUID.randomUUID(), NOW, new EventMetadata(null, null, "req-1", null));

    private static RoleId newRoleId() {
        return RoleId.of(UUID.randomUUID());
    }

    /** A distinct lower-case word per number, since permission actions contain letters only. */
    private static String letters(int number) {
        StringBuilder word = new StringBuilder();
        int n = number;
        do {
            word.append((char) ('a' + n % 26));
            n /= 26;
        } while (n > 0);
        return word.toString();
    }

    private static String code(BmpException e) {
        return e.errorCode().code();
    }

    @Nested
    class Roles {

        @Test
        void seedsSystemRolesWithTheirDefaults() {
            Role cashier = Role.seed(newRoleId(), TENANT, SystemRole.CASHIER);

            assertThat(cashier.code()).isEqualTo("CASHIER");
            assertThat(cashier.name()).isEqualTo("Cashier");
            assertThat(cashier.isSystem()).isTrue();
            assertThat(cashier.isActive()).isTrue();
            assertThat(cashier.isOwner()).isFalse();
            assertThat(cashier.permissions()).isEqualTo(SystemRole.CASHIER.defaultPermissions());
            assertThat(cashier.effectiveGrants()).isEqualTo(SystemRole.CASHIER.defaultPermissions());
            assertThat(cashier.version()).isZero();
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Role.seed(newRoleId(), TENANT, SystemRole.PLATFORM_ADMIN));
        }

        @Test
        void ownerAlwaysHoldsEveryTenantPermission() {
            Role owner = Role.seed(newRoleId(), TENANT, SystemRole.OWNER);

            assertThat(owner.isOwner()).isTrue();
            assertThatThrownBy(() -> owner.changePermissions(Set.of("sales:read")))
                    .isInstanceOfSatisfying(
                            BmpException.class, e -> assertThat(code(e)).isEqualTo("SYSTEM_ROLE_IMMUTABLE"));
            assertThat(owner.permissions()).containsExactly("*");
        }

        @Test
        void systemRolesCannotBeDeactivatedButTheirPermissionsCanChange() {
            Role manager = Role.seed(newRoleId(), TENANT, SystemRole.MANAGER);

            assertThatThrownBy(manager::deactivate)
                    .isInstanceOfSatisfying(
                            BmpException.class, e -> assertThat(code(e)).isEqualTo("SYSTEM_ROLE_IMMUTABLE"));
            manager.changePermissions(Set.of("sales:*", "tenant:read"));
            manager.rename("Store manager");

            assertThat(manager.permissions()).containsExactlyInAnyOrder("sales:*", "tenant:read");
            assertThat(manager.name()).isEqualTo("Store manager");
            assertThat(manager.status()).isEqualTo(RoleStatus.ACTIVE);
        }

        @Test
        void customRolesGrantNothingWhileInactive() {
            Role supervisor = Role.custom(newRoleId(), TENANT, "SUPERVISOR", "Supervisor", Set.of("sales:*"));

            supervisor.deactivate();
            assertThat(supervisor.effectiveGrants()).isEmpty();
            assertThat(supervisor.status()).isEqualTo(RoleStatus.INACTIVE);
            supervisor.activate();
            assertThat(supervisor.effectiveGrants()).containsExactly("sales:*");
            assertThat(supervisor.isSystem()).isFalse();
        }

        @Test
        void rejectsMalformedReservedAndPlatformCodesAndGrants() {
            assertThatThrownBy(() -> Role.custom(newRoleId(), TENANT, "lower", "x", Set.of()))
                    .isInstanceOf(ValidationFailure.class);
            assertThatThrownBy(() -> Role.custom(newRoleId(), TENANT, "OWNER", "x", Set.of()))
                    .isInstanceOfSatisfying(
                            BmpException.class, e -> assertThat(code(e)).isEqualTo("ROLE_CODE_TAKEN"));
            assertThatThrownBy(() -> Role.custom(newRoleId(), TENANT, "SNEAKY", "x", Set.of("platform:tenants")))
                    .isInstanceOfSatisfying(
                            BmpException.class, e -> assertThat(code(e)).isEqualTo("INVALID_PERMISSION"));
            assertThatThrownBy(() -> Role.custom(newRoleId(), TENANT, "BROKEN", "x", Set.of("Sales")))
                    .isInstanceOfSatisfying(
                            BmpException.class, e -> assertThat(code(e)).isEqualTo("INVALID_PERMISSION"));
            Set<String> tooMany = new HashSet<>();
            IntStream.rangeClosed(0, Role.MAX_PERMISSIONS).forEach(i -> tooMany.add("area:" + letters(i)));
            assertThatThrownBy(() -> Role.custom(newRoleId(), TENANT, "BIG", "x", tooMany))
                    .isInstanceOfSatisfying(
                            BmpException.class, e -> assertThat(code(e)).isEqualTo("INVALID_PERMISSION"));
        }
    }

    @Nested
    class Users {

        private final UserId subject = UserId.of(UUID.randomUUID());
        private final RoleId cashier = newRoleId();

        @Test
        void createsAnActiveUserWhoseIdIsTheSubject() {
            LocationId shop = LocationId.of(UUID.randomUUID());
            AppUser user = AppUser.create(subject, TENANT, "Nimal", shop, List.of(RoleGrant.everywhere(cashier)));

            assertThat(user.id()).isEqualTo(subject);
            assertThat(user.idpSubject()).isEqualTo(subject);
            assertThat(user.isActive()).isTrue();
            assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);
            assertThat(user.defaultLocationId()).isEqualTo(shop.value());
            assertThat(user.holds(cashier)).isTrue();
            assertThat(user.holds(newRoleId())).isFalse();
            assertThat(user.version()).isZero();
            assertThat(user.createdAt()).isEqualTo(Instant.EPOCH);
        }

        @Test
        void changesNameLocationStatusAndRoles() {
            AppUser user = AppUser.create(subject, TENANT, "Nimal", null, List.of());
            RoleId manager = newRoleId();
            UUID store = UUID.randomUUID();

            user.rename("Nimal P.");
            user.changeDefaultLocation(LocationId.of(store));
            user.disable();
            assertThat(user.isActive()).isFalse();
            user.enable();
            user.replaceRoles(List.of(new RoleGrant(manager.value(), List.of(store))));
            user.recordCreation(NOW, null);
            user.recordModification(NOW.plusSeconds(1), subject);

            assertThat(user.displayName()).isEqualTo("Nimal P.");
            assertThat(user.defaultLocationId()).isEqualTo(store);
            assertThat(user.isActive()).isTrue();
            assertThat(user.roles()).containsExactly(new RoleGrant(manager.value(), List.of(store)));
            assertThat(user.createdAt()).isEqualTo(NOW);
        }

        @Test
        void rejectsDuplicateAndTooManyRoles() {
            List<RoleGrant> twice = List.of(RoleGrant.everywhere(cashier), RoleGrant.everywhere(cashier));
            List<RoleGrant> tooMany = IntStream.rangeClosed(0, AppUser.MAX_ROLES)
                    .mapToObj(i -> RoleGrant.everywhere(newRoleId()))
                    .toList();

            assertThatThrownBy(() -> AppUser.create(subject, TENANT, "N", null, twice))
                    .isInstanceOf(ValidationFailure.class);
            assertThatThrownBy(() -> AppUser.create(subject, TENANT, "N", null, tooMany))
                    .isInstanceOf(ValidationFailure.class);
        }

        @Test
        void lastOwnerRuleKeepsOneActiveOwner() {
            assertThatThrownBy(() -> LastOwnerRule.check(subject, true, false, 1))
                    .isInstanceOfSatisfying(
                            BmpException.class, e -> assertThat(code(e)).isEqualTo("LAST_OWNER"));
            assertThatCode(() -> LastOwnerRule.check(subject, true, false, 2)).doesNotThrowAnyException();
            assertThatCode(() -> LastOwnerRule.check(subject, true, true, 1)).doesNotThrowAnyException();
            assertThatCode(() -> LastOwnerRule.check(subject, false, false, 0)).doesNotThrowAnyException();
        }
    }

    @Nested
    class Devices {

        @Test
        void registersAnActiveDeviceAndRecordsTheEvent() {
            DeviceId id = DeviceId.of(UUID.randomUUID());
            LocationId shop = LocationId.of(UUID.randomUUID());
            RegisterId register = RegisterId.of(UUID.randomUUID());

            Device device = Device.register(id, TENANT, "Till 1", shop, register, STAMPS);

            assertThat(device.id()).isEqualTo(id);
            assertThat(device.idpClientId()).isEqualTo("device-" + id.value());
            assertThat(device.isActive()).isTrue();
            assertThat(device.status()).isEqualTo(DeviceStatus.ACTIVE);
            assertThat(device.registeredAt()).isEqualTo(NOW);
            assertThat(device.registerId()).isEqualTo(register.value());
            assertThat(device.locationId()).isEqualTo(shop.value());
            assertThat(device.name()).isEqualTo("Till 1");
            assertThat(device.revokedAt()).isNull();
            assertThat(device.pullEvents()).singleElement().isInstanceOfSatisfying(DeviceRegistered.class, e -> {
                assertThat(e.deviceId()).isEqualTo(id);
                assertThat(e.aggregateVersion()).isZero();
                assertThat(e.registerId()).isEqualTo(register);
            });
            assertThat(device.pullEvents()).isEmpty();
        }

        @Test
        void revocationIsFinal() {
            Device device = Device.register(
                    DeviceId.of(UUID.randomUUID()), TENANT, "Till", LocationId.of(UUID.randomUUID()), null, STAMPS);
            device.recordCreation(NOW, null);
            device.recordModification(NOW, null);

            device.revoke("Stolen", NOW.plusSeconds(60));

            assertThat(device.isActive()).isFalse();
            assertThat(device.revokedAt()).isEqualTo(NOW.plusSeconds(60));
            assertThat(device.registerId()).isNull();
            assertThatThrownBy(() -> device.revoke("Again", NOW))
                    .isInstanceOfSatisfying(
                            BmpException.class, e -> assertThat(code(e)).isEqualTo("DEVICE_ALREADY_REVOKED"));
        }
    }
}
