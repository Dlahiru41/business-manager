package com.bmp.finance.config;

import com.bmp.finance.domain.error.FinanceErrorCode;
import com.bmp.persistence.migration.ModuleMigration;
import com.bmp.persistence.sequence.NumberSequences;
import com.bmp.web.problem.ConstraintErrorMapping;
import com.bmp.web.problem.ModuleMessages;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Wiring of the finance module: migrations, messages, numbering and constraint codes. */
@Configuration(proxyBeanMethods = false)
class FinanceModuleConfiguration {

    /** After payments (90): finance consumes every upstream module's events. */
    static final int MIGRATION_ORDER = 100;

    @Bean
    ModuleMigration financeMigration() {
        return new ModuleMigration("finance", MIGRATION_ORDER);
    }

    @Bean
    ModuleMessages financeMessages() {
        return new ModuleMessages("i18n/finance/messages");
    }

    @Bean
    NumberSequences financeNumbers(JdbcClient jdbc) {
        return new NumberSequences(jdbc, "finance");
    }

    @Bean
    ConstraintErrorMapping accountCodeTaken() {
        return ConstraintErrorMapping.of("uk_account_tenant_code", FinanceErrorCode.ACCOUNT_CODE_TAKEN);
    }

    @Bean
    ConstraintErrorMapping expenseCategoryCodeTaken() {
        return ConstraintErrorMapping.of(
                "uk_expense_category_tenant_code", FinanceErrorCode.EXPENSE_CATEGORY_CODE_TAKEN);
    }

    @Bean
    ConstraintErrorMapping journalAlreadyReversed() {
        return ConstraintErrorMapping.of("uk_journal_entry_tenant_reverses", FinanceErrorCode.JOURNAL_ALREADY_REVERSED);
    }
}
