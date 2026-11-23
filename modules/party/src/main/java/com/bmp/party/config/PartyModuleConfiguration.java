package com.bmp.party.config;

import com.bmp.party.domain.error.PartyErrorCode;
import com.bmp.persistence.migration.ModuleMigration;
import com.bmp.web.problem.ConstraintErrorMapping;
import com.bmp.web.problem.ModuleMessages;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the party module with the platform: migrations, messages and constraint error codes. */
@Configuration(proxyBeanMethods = false)
class PartyModuleConfiguration {

    /** After the tenant module (10), the only one party depends on. */
    static final int MIGRATION_ORDER = 50;

    @Bean
    ModuleMigration partyMigration() {
        return new ModuleMigration("party", MIGRATION_ORDER);
    }

    @Bean
    ModuleMessages partyMessages() {
        return new ModuleMessages("i18n/party/messages");
    }

    @Bean
    ConstraintErrorMapping customerGroupCodeTaken() {
        return ConstraintErrorMapping.of("uk_customer_group_tenant_code", PartyErrorCode.CUSTOMER_GROUP_CODE_TAKEN);
    }
}
