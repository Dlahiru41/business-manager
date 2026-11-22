package com.bmp.iam.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bmp.iam.domain.model.Device;
import com.bmp.iam.domain.model.Role;
import com.bmp.iam.domain.model.RoleId;
import com.bmp.iam.domain.port.DeviceRepository;
import com.bmp.iam.domain.port.RoleRepository;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.event.EventStamp;
import com.bmp.shared.id.DeviceId;
import com.bmp.shared.id.LocationId;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.security.SystemRole;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AuthorizationLookupsTest {

    private static final TenantId TENANT = TenantId.of(UUID.randomUUID());

    private final RoleRepository roles = mock(RoleRepository.class);
    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private RolePermissionResolver resolver;

    @BeforeEach
    void setUp() {
        when(transactions.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        resolver = new RolePermissionResolver(roles, transactions);
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static Role custom(String code, Set<String> grants) {
        return Role.custom(RoleId.of(UUID.randomUUID()), TENANT, code, code, grants);
    }

    @Test
    void expandsStoredRolesAndFallsBackToSystemDefaultsBeforeSeeding() {
        Role supervisor = custom("SUPERVISOR", Set.of("sales:*"));
        Role packer = custom("PACKER", Set.of("inventory:count"));
        packer.deactivate();
        when(roles.findByCodeIn(anyCollection())).thenReturn(List.of(supervisor, packer));

        Set<String> grants =
                resolver.grantsFor(TENANT, Set.of("SUPERVISOR", "PACKER", "CASHIER", "NOBODY", "platform_admin"));

        assertThat(grants)
                .contains("sales:*")
                .containsAll(SystemRole.CASHIER.defaultPermissions())
                .doesNotContain("inventory:count", "platform:*");
        assertThat(resolver.grantsFor(TENANT, Set.of())).isEmpty();
    }

    @Test
    void cachesPerTenantAndRoleSetUntilEvicted() {
        when(roles.findByCodeIn(anyCollection())).thenReturn(List.of());

        resolver.grantsFor(TENANT, Set.of("CASHIER"));
        resolver.grantsFor(TENANT, Set.of("CASHIER"));
        resolver.evictAfterCommit(TENANT);
        resolver.grantsFor(TENANT, Set.of("CASHIER"));

        verify(roles, times(2)).findByCodeIn(anyCollection());
    }

    @Test
    void evictsOnlyAfterTheTransactionCommits() {
        when(roles.findByCodeIn(anyCollection())).thenReturn(List.of());
        resolver.grantsFor(TENANT, Set.of("OWNER"));
        TransactionSynchronizationManager.initSynchronization();

        resolver.evictAfterCommit(TENANT);
        resolver.grantsFor(TENANT, Set.of("OWNER"));
        verify(roles, times(1)).findByCodeIn(anyCollection());

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        resolver.grantsFor(TENANT, Set.of("OWNER"));
        verify(roles, times(2)).findByCodeIn(anyCollection());
    }

    @Test
    void devicesAreActiveOnlyWhileKnownAndNotRevoked() {
        DeviceStatusLookup lookup = new DeviceStatusLookup(devices, transactions);
        DeviceId known = DeviceId.of(UUID.randomUUID());
        Device device = Device.register(
                known,
                TENANT,
                "Till",
                LocationId.of(UUID.randomUUID()),
                null,
                () -> new EventStamp(UUID.randomUUID(), Instant.EPOCH, EventMetadata.empty()));
        when(devices.findById(known.value())).thenReturn(Optional.of(device));
        when(devices.findById(any()))
                .thenAnswer(invocation ->
                        known.value().equals(invocation.getArgument(0)) ? Optional.of(device) : Optional.empty());

        assertThat(lookup.isActive(TENANT, known)).isTrue();
        assertThat(lookup.isActive(TENANT, DeviceId.of(UUID.randomUUID()))).isFalse();

        device.revoke("Lost", Instant.EPOCH);
        assertThat(lookup.isActive(TENANT, known)).as("cached").isTrue();
        lookup.evict(TENANT, known);
        assertThat(lookup.isActive(TENANT, known)).isFalse();
    }
}
