package com.bmp.catalog;

import com.bmp.test.security.TestJwtConfiguration;
import com.bmp.test.time.MutableClock;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** Boots the catalog module with the module tests create tenants through (tenant) and the platform libraries. */
@SpringBootApplication(scanBasePackages = {"com.bmp.catalog", "com.bmp.tenant"})
@AutoConfigurationPackage(basePackages = {"com.bmp.catalog", "com.bmp.tenant"})
@Import(TestJwtConfiguration.class)
public class CatalogTestApplication {

    @Bean
    MutableClock clock() {
        return MutableClock.at("2026-11-21T09:00:00Z");
    }
}
