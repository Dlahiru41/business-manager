package com.bmp.inventory.config;

import com.bmp.persistence.migration.ModuleMigration;
import com.bmp.web.problem.ModuleMessages;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the inventory module with the platform: migrations and messages. */
@Configuration(proxyBeanMethods = false)
class InventoryModuleConfiguration {

    /** After the modules inventory replicates or listens to (tenant 10, catalog 30, procurement 60). */
    static final int MIGRATION_ORDER = 70;

    @Bean
    ModuleMigration inventoryMigration() {
        return new ModuleMigration("inventory", MIGRATION_ORDER);
    }

    @Bean
    ModuleMessages inventoryMessages() {
        return new ModuleMessages("i18n/inventory/messages");
    }
}
