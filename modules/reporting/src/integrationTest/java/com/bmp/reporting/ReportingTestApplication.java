package com.bmp.reporting;

import com.bmp.test.security.TestJwtConfiguration;
import com.bmp.test.time.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** Reporting with the tenant module; other modules' events are published by the tests, their APIs faked. */
@SpringBootApplication(scanBasePackages = {"com.bmp.reporting", "com.bmp.tenant"})
@AutoConfigurationPackage(basePackages = {"com.bmp.reporting", "com.bmp.tenant"})
@Import({TestJwtConfiguration.class, Fakes.class})
public class ReportingTestApplication {

    @Bean
    MutableClock clock() {
        return MutableClock.at("2026-11-25T09:00:00Z");
    }

    @Bean
    SimpleMeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }
}
