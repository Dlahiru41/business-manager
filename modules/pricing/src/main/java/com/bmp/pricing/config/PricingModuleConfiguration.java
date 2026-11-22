package com.bmp.pricing.config;

import com.bmp.persistence.migration.ModuleMigration;
import com.bmp.pricing.domain.error.PricingErrorCode;
import com.bmp.web.problem.ConstraintErrorMapping;
import com.bmp.web.problem.ModuleMessages;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the pricing module with the platform: migrations, messages and constraint error codes. */
@Configuration(proxyBeanMethods = false)
class PricingModuleConfiguration {

    /** After the catalog (30), which pricing depends on. */
    static final int MIGRATION_ORDER = 40;

    @Bean
    ModuleMigration pricingMigration() {
        return new ModuleMigration("pricing", MIGRATION_ORDER);
    }

    @Bean
    ModuleMessages pricingMessages() {
        return new ModuleMessages("i18n/pricing/messages");
    }

    @Bean
    ConstraintErrorMapping priceListCodeTaken() {
        return ConstraintErrorMapping.of("uk_price_list_tenant_code", PricingErrorCode.PRICE_LIST_CODE_TAKEN);
    }

    @Bean
    ConstraintErrorMapping priceRuleCodeTaken() {
        return ConstraintErrorMapping.of("uk_price_rule_tenant_code", PricingErrorCode.PRICE_RULE_CODE_TAKEN);
    }

    @Bean
    ConstraintErrorMapping taxCategoryCodeTaken() {
        return ConstraintErrorMapping.of("uk_tax_category_tenant_code", PricingErrorCode.TAX_CATEGORY_CODE_TAKEN);
    }

    @Bean
    ConstraintErrorMapping priceKeyTaken() {
        return ConstraintErrorMapping.of("uk_price_list_unit_min_qty_from", PricingErrorCode.PRICE_OVERLAP);
    }
}
