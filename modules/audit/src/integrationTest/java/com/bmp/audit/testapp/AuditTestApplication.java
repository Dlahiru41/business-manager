package com.bmp.audit.testapp;

import com.bmp.test.security.TestJwtConfiguration;
import com.bmp.test.time.MutableClock;
import com.bmp.web.security.TenantStatusProvider;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** Boots the audit module alone, with a stand-in controller; every tenant counts as active. */
@SpringBootApplication(scanBasePackages = "com.bmp.audit")
@Import(TestJwtConfiguration.class)
public class AuditTestApplication {

    @Bean
    MutableClock clock() {
        return MutableClock.at("2026-11-22T09:00:00Z");
    }

    @Bean
    TenantStatusProvider activeTenants() {
        return tenant -> TenantStatusProvider.TenantStatus.ACTIVE;
    }
}
