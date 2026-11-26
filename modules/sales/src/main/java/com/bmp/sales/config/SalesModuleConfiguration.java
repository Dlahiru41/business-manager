package com.bmp.sales.config;

import com.bmp.persistence.migration.ModuleMigration;
import com.bmp.persistence.sequence.NumberSequences;
import com.bmp.sales.api.spi.CreditExposureProvider;
import com.bmp.sales.domain.error.SalesErrorCode;
import com.bmp.sales.domain.port.BillRepository;
import com.bmp.web.problem.ConstraintErrorMapping;
import com.bmp.web.problem.ModuleMessages;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Registers the sales module with the platform: migrations, messages, numbers and the credit SPI default. */
@Configuration(proxyBeanMethods = false)
class SalesModuleConfiguration {

    /** After tenant (10), iam (20), catalog (30), pricing (40) and party (50); before payments and inventory's sales. */
    static final int MIGRATION_ORDER = 80;

    @Bean
    ModuleMigration salesMigration() {
        return new ModuleMigration("sales", MIGRATION_ORDER);
    }

    @Bean
    ModuleMessages salesMessages() {
        return new ModuleMessages("i18n/sales/messages");
    }

    /** SAL-10: per-tenant credit note numbers {@code CN-000001} (ADR-012). */
    @Bean
    NumberSequences salesNumbers(JdbcClient jdbc) {
        return new NumberSequences(jdbc, "sales");
    }

    /**
     * Rule SAL-07: until finance knows receivables (phase 04), a customer's exposure is Σ CREDIT tenders of their bills
     * less what credit notes credited back; collections are ignored, so it errs on the safe side. A fallback bean: a
     * real provider replaces it.
     */
    @Bean
    @Fallback
    CreditExposureProvider billCreditExposure(BillRepository bills) {
        return customer -> bills.creditExposure(customer.value());
    }

    @Bean
    ConstraintErrorMapping registerCodeTaken() {
        return ConstraintErrorMapping.of("uk_register_tenant_code", SalesErrorCode.REGISTER_CODE_TAKEN);
    }

    @Bean
    ConstraintErrorMapping billPrefixTaken() {
        return ConstraintErrorMapping.of("uk_register_tenant_prefix", SalesErrorCode.BILL_PREFIX_TAKEN);
    }

    @Bean
    ConstraintErrorMapping billNumberTaken() {
        return ConstraintErrorMapping.of("uk_bill_tenant_number", SalesErrorCode.BILL_NUMBER_CONFLICT);
    }

    @Bean
    ConstraintErrorMapping shiftAlreadyOpen() {
        return ConstraintErrorMapping.of("uk_shift_open_per_register", SalesErrorCode.SHIFT_ALREADY_OPEN);
    }
}
