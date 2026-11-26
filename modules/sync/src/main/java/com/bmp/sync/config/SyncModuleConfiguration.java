package com.bmp.sync.config;

import com.bmp.persistence.migration.ModuleMigration;
import com.bmp.web.problem.ModuleMessages;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the sync module with the platform: migrations and messages. */
@Configuration(proxyBeanMethods = false)
class SyncModuleConfiguration {

    /** After payments (90). */
    static final int MIGRATION_ORDER = 100;

    @Bean
    ModuleMigration syncMigration() {
        return new ModuleMigration("sync", MIGRATION_ORDER);
    }

    @Bean
    ModuleMessages syncMessages() {
        return new ModuleMessages("i18n/sync/messages");
    }
}
