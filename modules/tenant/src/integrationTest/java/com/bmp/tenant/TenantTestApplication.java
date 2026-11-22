package com.bmp.tenant;

import com.bmp.test.security.TestJwtConfiguration;
import com.bmp.test.time.MutableClock;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** Boots the tenant module alone with the platform libraries. */
@SpringBootApplication
@Import(TestJwtConfiguration.class)
public class TenantTestApplication {

    @Bean
    MutableClock clock() {
        return MutableClock.at("2026-11-21T09:00:00Z");
    }
}
