package com.bmp.pricing;

import com.bmp.test.security.TestJwtConfiguration;
import com.bmp.test.time.MutableClock;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** Boots the pricing module with the modules it depends on (tenant, catalog) and the platform libraries. */
@SpringBootApplication(scanBasePackages = {"com.bmp.pricing", "com.bmp.catalog", "com.bmp.tenant"})
@AutoConfigurationPackage(basePackages = {"com.bmp.pricing", "com.bmp.catalog", "com.bmp.tenant"})
@Import(TestJwtConfiguration.class)
public class PricingTestApplication {

    @Bean
    MutableClock clock() {
        return MutableClock.at("2026-11-21T09:00:00Z");
    }
}
