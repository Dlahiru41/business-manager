package com.bmp.persistence.it;

import com.bmp.persistence.migration.ModuleMigration;
import com.bmp.persistence.sequence.NumberSequences;
import com.bmp.test.time.MutableClock;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootApplication
public class PersistenceTestApplication {

    public static final String START = "2026-11-21T09:00:00Z";

    @Bean
    ModuleMigration sampleMigration() {
        return new ModuleMigration("sample", 1000);
    }

    @Bean
    MutableClock clock() {
        return MutableClock.at(START);
    }

    @Bean
    NumberSequences sampleNumbers(JdbcClient jdbcClient) {
        return new NumberSequences(jdbcClient, "sample");
    }

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
