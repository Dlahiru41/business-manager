package com.bmp.payments.config;

import com.bmp.payments.domain.error.PaymentsErrorCode;
import com.bmp.persistence.migration.ModuleMigration;
import com.bmp.web.problem.ConstraintErrorMapping;
import com.bmp.web.problem.ModuleMessages;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the payments module with the platform: migrations, messages and constraint errors. */
@Configuration(proxyBeanMethods = false)
class PaymentsModuleConfiguration {

    /** After sales (80): payments reads registers through sales' API. */
    static final int MIGRATION_ORDER = 90;

    @Bean
    ModuleMigration paymentsMigration() {
        return new ModuleMigration("payments", MIGRATION_ORDER);
    }

    @Bean
    ModuleMessages paymentsMessages() {
        return new ModuleMessages("i18n/payments/messages");
    }

    /** PAY-04: two drawers opened at once for one register. */
    @Bean
    ConstraintErrorMapping drawerAlreadyOpen() {
        return ConstraintErrorMapping.of("uk_drawer_session_open_per_register", PaymentsErrorCode.DRAWER_ALREADY_OPEN);
    }
}
