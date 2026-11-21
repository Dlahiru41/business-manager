package com.bmp.web.testapp;

import com.bmp.shared.id.DeviceId;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.security.SystemRole;
import com.bmp.test.security.JwtTestTokens;
import com.bmp.test.security.TestJwtConfiguration;
import com.bmp.web.idempotency.InMemoryIdempotencyStore;
import com.bmp.web.problem.ConstraintErrorMapping;
import com.bmp.web.problem.ModuleMessages;
import com.bmp.web.security.DeviceStatusProvider;
import com.bmp.web.security.TenantStatusProvider;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

@SpringBootApplication
@Import(TestJwtConfiguration.class)
public class WebSupportTestApplication {

    /** Tenant of the default token sent by API tests that are not about security. */
    public static final TenantId DEFAULT_TENANT = TenantId.of(UUID.fromString("01934f5a-6d1c-7000-8000-00000000f001"));

    @Bean
    ModuleMessages testMessages() {
        return new ModuleMessages("i18n/test/messages");
    }

    @Bean
    ConstraintErrorMapping thingNameTaken() {
        return ConstraintErrorMapping.of("uk_thing_tenant_name", TestErrorCode.THING_NAME_TAKEN);
    }

    @Bean
    InMemoryIdempotencyStore idempotencyStore() {
        return new InMemoryIdempotencyStore();
    }

    @Bean
    TestTenants testTenants() {
        return new TestTenants();
    }

    @Bean
    TestDevices testDevices() {
        return new TestDevices();
    }

    /** Every request carries an owner token unless the test is about security itself. */
    @Bean
    @Profile("!security-api")
    MockMvcBuilderCustomizer defaultOwnerToken() {
        String token = JwtTestTokens.forRole(DEFAULT_TENANT, SystemRole.OWNER);
        return builder ->
                builder.defaultRequest(MockMvcRequestBuilders.get("/").header("Authorization", "Bearer " + token));
    }

    /** Tenant statuses for tests; tenants are ACTIVE unless set otherwise. */
    public static class TestTenants implements TenantStatusProvider {
        private final Map<TenantId, TenantStatus> statuses = new ConcurrentHashMap<>();

        @Override
        public TenantStatus statusOf(TenantId tenant) {
            return statuses.getOrDefault(tenant, TenantStatus.ACTIVE);
        }

        public void set(TenantId tenant, TenantStatus status) {
            statuses.put(tenant, status);
        }
    }

    /** Devices are active unless revoked. */
    public static class TestDevices implements DeviceStatusProvider {
        private final Set<DeviceId> revoked = ConcurrentHashMap.newKeySet();

        @Override
        public boolean isActive(TenantId tenant, DeviceId device) {
            return !revoked.contains(device);
        }

        public void revoke(DeviceId device) {
            revoked.add(device);
        }
    }
}
