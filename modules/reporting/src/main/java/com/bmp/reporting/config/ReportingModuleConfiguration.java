package com.bmp.reporting.config;

import com.bmp.persistence.migration.ModuleMigration;
import com.bmp.web.problem.ModuleMessages;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wiring of the reporting module: migrations and messages. */
@Configuration(proxyBeanMethods = false)
class ReportingModuleConfiguration {

    /** After finance (100) and documents (110): reporting reads every module's events. */
    static final int MIGRATION_ORDER = 120;

    @Bean
    ModuleMigration reportingMigration() {
        return new ModuleMigration("reporting", MIGRATION_ORDER);
    }

    @Bean
    ModuleMessages reportingMessages() {
        return new ModuleMessages("i18n/reporting/messages");
    }
}
