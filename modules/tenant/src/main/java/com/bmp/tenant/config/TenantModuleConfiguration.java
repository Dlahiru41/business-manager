package com.bmp.tenant.config;

import com.bmp.persistence.migration.ModuleMigration;
import com.bmp.tenant.domain.error.TenantErrorCode;
import com.bmp.web.problem.ConstraintErrorMapping;
import com.bmp.web.problem.ModuleMessages;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the tenant module with the platform: migrations, messages and constraint error codes. */
@Configuration(proxyBeanMethods = false)
class TenantModuleConfiguration {

    /** Tenant is the most upstream module, so its schema migrates first. */
    static final int MIGRATION_ORDER = 10;

    @Bean
    ModuleMigration tenantMigration() {
        return new ModuleMigration("tenant", MIGRATION_ORDER);
    }

    @Bean
    ModuleMessages tenantMessages() {
        return new ModuleMessages("i18n/tenant/messages");
    }

    @Bean
    ConstraintErrorMapping locationNameTaken() {
        return ConstraintErrorMapping.of("uk_location_tenant_name", TenantErrorCode.LOCATION_NAME_TAKEN);
    }

    @Bean
    ConstraintErrorMapping receiptTemplateNameTaken() {
        return ConstraintErrorMapping.of(
                "uk_receipt_template_tenant_name", TenantErrorCode.RECEIPT_TEMPLATE_NAME_TAKEN);
    }
}
