package com.bmp.sync;

import com.bmp.iam.api.IamQueries;
import com.bmp.iam.api.dto.DeviceBinding;
import com.bmp.shared.id.DeviceId;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import com.bmp.shared.security.PermissionPattern;
import com.bmp.sync.api.events.BillNumberGapDetected;
import com.bmp.test.security.TestJwtConfiguration;
import com.bmp.test.time.MutableClock;
import com.bmp.web.security.DeviceStatusProvider;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;

/**
 * Boots sync with every module a till's upload reaches (sales, payments, inventory) and its data comes from (catalog,
 * pricing, party, documents, tenant). Iam is not here: {@link Devices} answers {@code IamQueries} for the devices a test binds.
 */
@SpringBootApplication(
        scanBasePackages = {
            "com.bmp.sync",
            "com.bmp.sales",
            "com.bmp.payments",
            "com.bmp.inventory",
            "com.bmp.catalog",
            "com.bmp.pricing",
            "com.bmp.party",
            "com.bmp.documents",
            "com.bmp.tenant"
        })
@AutoConfigurationPackage(
        basePackages = {
            "com.bmp.sync",
            "com.bmp.sales",
            "com.bmp.payments",
            "com.bmp.inventory",
            "com.bmp.catalog",
            "com.bmp.pricing",
            "com.bmp.party",
            "com.bmp.documents",
            "com.bmp.tenant"
        })
@Import(TestJwtConfiguration.class)
public class SyncTestApplication {

    @Bean
    MutableClock clock() {
        return MutableClock.at("2026-11-25T09:00:00Z");
    }

    @Bean
    Devices devices() {
        return new Devices();
    }

    @Bean
    Gaps gaps() {
        return new Gaps();
    }

    /** Devices bound to registers and users with grants, as iam would answer. */
    public static class Devices implements IamQueries, DeviceStatusProvider {

        private final Map<UUID, DeviceBinding> devices = new ConcurrentHashMap<>();
        private final Map<UUID, Set<String>> grants = new ConcurrentHashMap<>();

        public DeviceId bind(UUID registerId, UUID locationId) {
            DeviceId device = DeviceId.of(UUID.randomUUID());
            devices.put(device.value(), new DeviceBinding(device, registerId, locationId, true));
            return device;
        }

        /** An active device bound to no register. */
        public DeviceId unbound(UUID locationId) {
            DeviceId device = DeviceId.of(UUID.randomUUID());
            devices.put(device.value(), new DeviceBinding(device, null, locationId, true));
            return device;
        }

        @Override
        public boolean isActive(TenantId tenant, DeviceId device) {
            DeviceBinding binding = devices.get(device.value());
            return binding != null && binding.active();
        }

        public UUID user(String... permissions) {
            UUID user = UUID.randomUUID();
            grants.put(user, Set.of(permissions));
            return user;
        }

        @Override
        public boolean userHasPermission(UserId user, String permission) {
            return grants.getOrDefault(user.value(), Set.of()).stream()
                    .anyMatch(grant -> PermissionPattern.matches(grant, permission));
        }

        @Override
        public Optional<DeviceBinding> findDevice(DeviceId device) {
            return Optional.ofNullable(devices.get(device.value()));
        }
    }

    /** Gap events of every thread. */
    public static class Gaps {

        private final List<BillNumberGapDetected> events = new CopyOnWriteArrayList<>();

        @EventListener
        public void on(BillNumberGapDetected event) {
            events.add(event);
        }

        public List<BillNumberGapDetected> of(UUID registerId) {
            return events.stream()
                    .filter(e -> e.registerId().equals(registerId))
                    .toList();
        }
    }
}
