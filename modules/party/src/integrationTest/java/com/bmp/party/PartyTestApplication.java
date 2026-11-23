package com.bmp.party;

import com.bmp.test.security.TestJwtConfiguration;
import com.bmp.test.time.MutableClock;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** Boots the party module with the module it depends on (tenant) and the platform libraries. */
@SpringBootApplication(scanBasePackages = {"com.bmp.party", "com.bmp.tenant"})
@AutoConfigurationPackage(basePackages = {"com.bmp.party", "com.bmp.tenant"})
@Import(TestJwtConfiguration.class)
public class PartyTestApplication {

    @Bean
    MutableClock clock() {
        return MutableClock.at("2026-11-23T09:00:00Z");
    }
}
