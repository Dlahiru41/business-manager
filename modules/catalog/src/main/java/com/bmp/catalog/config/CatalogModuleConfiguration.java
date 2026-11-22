package com.bmp.catalog.config;

import com.bmp.catalog.domain.error.CatalogErrorCode;
import com.bmp.persistence.migration.ModuleMigration;
import com.bmp.web.problem.ConstraintErrorMapping;
import com.bmp.web.problem.ModuleMessages;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the catalog module with the platform: migrations, messages and constraint error codes. */
@Configuration(proxyBeanMethods = false)
class CatalogModuleConfiguration {

    /** After tenant (10) and iam (20); pricing, which depends on the catalog, migrates after it. */
    static final int MIGRATION_ORDER = 30;

    @Bean
    ModuleMigration catalogMigration() {
        return new ModuleMigration("catalog", MIGRATION_ORDER);
    }

    @Bean
    ModuleMessages catalogMessages() {
        return new ModuleMessages("i18n/catalog/messages");
    }

    // Unique keys of the catalog schema and the error code each violation means (04 par. 3).

    @Bean
    ConstraintErrorMapping productCodeTaken() {
        return ConstraintErrorMapping.of("uk_product_tenant_code", CatalogErrorCode.PRODUCT_CODE_TAKEN);
    }

    @Bean
    ConstraintErrorMapping skuTaken() {
        return ConstraintErrorMapping.of("uk_variant_tenant_sku", CatalogErrorCode.SKU_ALREADY_EXISTS);
    }

    @Bean
    ConstraintErrorMapping barcodeTaken() {
        return ConstraintErrorMapping.of("pk_barcode", CatalogErrorCode.BARCODE_ALREADY_EXISTS);
    }

    @Bean
    ConstraintErrorMapping unitCodeTaken() {
        return ConstraintErrorMapping.of("uk_unit_of_measure_tenant_code", CatalogErrorCode.UNIT_CODE_TAKEN);
    }

    @Bean
    ConstraintErrorMapping packagingUnitCodeTaken() {
        return ConstraintErrorMapping.of("uk_packaging_unit_variant_code", CatalogErrorCode.PACKAGING_UNIT_CODE_TAKEN);
    }

    @Bean
    ConstraintErrorMapping categoryNameTaken() {
        return ConstraintErrorMapping.of("uk_category_tenant_parent_name", CatalogErrorCode.CATEGORY_NAME_TAKEN);
    }

    @Bean
    ConstraintErrorMapping attributeCodeTaken() {
        return ConstraintErrorMapping.of("uk_attribute_type_product_code", CatalogErrorCode.ATTRIBUTE_CODE_TAKEN);
    }

    @Bean
    ConstraintErrorMapping attributeValueCodeTaken() {
        return ConstraintErrorMapping.of("uk_attribute_value_type_code", CatalogErrorCode.ATTRIBUTE_VALUE_CODE_TAKEN);
    }

    @Bean
    ConstraintErrorMapping definitionCodeTaken() {
        return ConstraintErrorMapping.of(
                "uk_transformation_definition_tenant_code", CatalogErrorCode.DEFINITION_CODE_TAKEN);
    }
}
