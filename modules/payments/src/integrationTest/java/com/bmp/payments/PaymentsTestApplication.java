package com.bmp.payments;

import com.bmp.iam.api.IamQueries;
import com.bmp.iam.api.dto.DeviceBinding;
import com.bmp.payments.api.events.DrawerReconciled;
import com.bmp.payments.api.events.PaymentCaptured;
import com.bmp.payments.api.events.PaymentRefunded;
import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.id.DeviceId;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import com.bmp.shared.security.PermissionPattern;
import com.bmp.test.security.TestJwtConfiguration;
import com.bmp.test.time.MutableClock;
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
 * Boots payments with sales and the modules sales reads (tenant, catalog, pricing, party). Iam is not here: approvers come from
 * {@link Approvers}, the way iam answers {@code IamQueries} (its own tests cover the real lookup).
 */
@SpringBootApplication(
        scanBasePackages = {
            "com.bmp.payments",
            "com.bmp.sales",
            "com.bmp.catalog",
            "com.bmp.pricing",
            "com.bmp.party",
            "com.bmp.tenant"
        })
@AutoConfigurationPackage(
        basePackages = {
            "com.bmp.payments",
            "com.bmp.sales",
            "com.bmp.catalog",
            "com.bmp.pricing",
            "com.bmp.party",
            "com.bmp.tenant"
        })
@Import(TestJwtConfiguration.class)
public class PaymentsTestApplication {

    @Bean
    MutableClock clock() {
        return MutableClock.at("2026-11-25T09:00:00Z");
    }

    @Bean
    Approvers approvers() {
        return new Approvers();
    }

    @Bean
    PaymentEvents paymentEvents() {
        return new PaymentEvents();
    }

    /** The payments events of every thread: the sales listeners publish outside the test's thread. */
    public static class PaymentEvents {

        private final List<Object> events = new CopyOnWriteArrayList<>();

        @EventListener
        public void on(PaymentCaptured event) {
            events.add(event);
        }

        @EventListener
        public void on(PaymentRefunded event) {
            events.add(event);
        }

        @EventListener
        public void on(DrawerReconciled event) {
            events.add(event);
        }

        public <T extends DomainEvent> List<T> of(Class<T> type, TenantId tenant) {
            return events.stream()
                    .filter(type::isInstance)
                    .map(type::cast)
                    .filter(e -> e.tenantId().equals(tenant))
                    .toList();
        }
    }

    /** Users a test names as approvers, with their grants. */
    public static class Approvers implements IamQueries {

        private final Map<UUID, Set<String>> grants = new ConcurrentHashMap<>();

        public UUID add(String... permissions) {
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
            return Optional.empty();
        }
    }
}
