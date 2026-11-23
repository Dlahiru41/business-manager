package com.bmp.procurement.config;

import com.bmp.persistence.migration.ModuleMigration;
import com.bmp.persistence.sequence.NumberSequences;
import com.bmp.procurement.api.spi.ReorderSuggestionProvider;
import com.bmp.procurement.domain.error.ProcurementErrorCode;
import com.bmp.web.problem.ConstraintErrorMapping;
import com.bmp.web.problem.ModuleMessages;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Registers the procurement module with the platform: migrations, messages, numbers and the reorder SPI default. */
@Configuration(proxyBeanMethods = false)
class ProcurementModuleConfiguration {

    /** After tenant (10), catalog (30) and party (50); before inventory (70), which listens to it. */
    static final int MIGRATION_ORDER = 60;

    @Bean
    ModuleMigration procurementMigration() {
        return new ModuleMigration("procurement", MIGRATION_ORDER);
    }

    @Bean
    ModuleMessages procurementMessages() {
        return new ModuleMessages("i18n/procurement/messages");
    }

    /** PRO-07: per-tenant PO and GRN numbers from {@code procurement.number_sequence} (ADR-012). */
    @Bean
    NumberSequences procurementNumbers(JdbcClient jdbc) {
        return new NumberSequences(jdbc, "procurement");
    }

    /**
     * Rule PRO-06: nothing to reorder until a module that knows stock (inventory) provides suggestions (01 §2). A
     * fallback bean, so any other provider wins regardless of the order configurations are processed in.
     */
    @Bean
    @Fallback
    ReorderSuggestionProvider noReorderSuggestions() {
        return location -> List.of();
    }

    @Bean
    ConstraintErrorMapping supplierInvoiceRefTaken() {
        return ConstraintErrorMapping.of(
                "uk_supplier_invoice_tenant_supplier_ref", ProcurementErrorCode.SUPPLIER_INVOICE_REF_TAKEN);
    }
}
