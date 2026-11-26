package com.bmp.documents;

import com.bmp.iam.api.IamQueries;
import com.bmp.iam.api.dto.DeviceBinding;
import com.bmp.shared.id.DeviceId;
import com.bmp.shared.id.UserId;
import com.bmp.shared.security.PermissionPattern;
import com.bmp.test.security.TestJwtConfiguration;
import com.bmp.test.time.MutableClock;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Boots documents with sales and the modules sales reads (tenant, catalog, pricing, party). Iam is not here: approvers come from
 * {@link Approvers}, the way iam answers {@code IamQueries} (its own tests cover the real lookup).
 */
@SpringBootApplication(
        scanBasePackages = {
            "com.bmp.documents",
            "com.bmp.sales",
            "com.bmp.catalog",
            "com.bmp.pricing",
            "com.bmp.party",
            "com.bmp.tenant"
        })
@AutoConfigurationPackage(
        basePackages = {
            "com.bmp.documents",
            "com.bmp.sales",
            "com.bmp.catalog",
            "com.bmp.pricing",
            "com.bmp.party",
            "com.bmp.tenant"
        })
@Import(TestJwtConfiguration.class)
public class DocumentsTestApplication {

    @Bean
    MutableClock clock() {
        return MutableClock.at("2026-11-25T09:00:00Z");
    }

    @Bean
    Approvers approvers() {
        return new Approvers();
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
