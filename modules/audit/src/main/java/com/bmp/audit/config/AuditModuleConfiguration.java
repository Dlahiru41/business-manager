package com.bmp.audit.config;

import com.bmp.audit.adapter.in.web.AuditInterceptor;
import com.bmp.audit.api.AuditWriter;
import com.bmp.persistence.migration.ModuleMigration;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the audit module: its migrations and the interceptor that records API writes. */
@Configuration(proxyBeanMethods = false)
class AuditModuleConfiguration {

    /** Audit only consumes events, so its schema can migrate last. */
    static final int MIGRATION_ORDER = 900;

    @Bean
    ModuleMigration auditMigration() {
        return new ModuleMigration("audit", MIGRATION_ORDER);
    }

    @Bean
    AuditInterceptor auditInterceptor(AuditWriter writer, Clock clock) {
        return new AuditInterceptor(writer, clock);
    }

    @Bean
    WebMvcConfigurer auditInterceptorRegistration(AuditInterceptor interceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor).addPathPatterns("/api/**");
            }
        };
    }
}
