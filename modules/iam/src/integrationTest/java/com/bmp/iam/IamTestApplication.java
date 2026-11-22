package com.bmp.iam;

import com.bmp.test.security.TestJwtConfiguration;
import com.bmp.test.time.MutableClock;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/** Boots the iam module with the module it depends on (tenant) and the platform libraries. */
@SpringBootApplication(scanBasePackages = {"com.bmp.iam", "com.bmp.tenant"})
@AutoConfigurationPackage(basePackages = {"com.bmp.iam", "com.bmp.tenant"})
@Import(TestJwtConfiguration.class)
public class IamTestApplication {

    @Bean
    MutableClock clock() {
        return MutableClock.at("2026-11-21T09:00:00Z");
    }

    /** Replaces the Keycloak gateway; the real adapter is covered by {@code KeycloakIdentityProviderIT}. */
    @Bean
    @Primary
    FakeIdentityProvider fakeIdentityProvider() {
        return new FakeIdentityProvider();
    }
}
