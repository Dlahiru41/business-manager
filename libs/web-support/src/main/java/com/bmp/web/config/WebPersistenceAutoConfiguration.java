package com.bmp.web.config;

import com.bmp.persistence.config.PersistenceAutoConfiguration;
import com.bmp.persistence.jdbc.PlatformAdminJdbc;
import com.bmp.web.idempotency.IdempotencyCleanupJob;
import com.bmp.web.idempotency.IdempotencyStore;
import com.bmp.web.idempotency.JdbcIdempotencyStore;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/** Database-backed web infrastructure: the durable idempotency store and its cleanup job. */
@AutoConfiguration(
        after = PersistenceAutoConfiguration.class,
        afterName = {
            "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration",
            "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"
        })
@ConditionalOnBean(DataSource.class)
public class WebPersistenceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(IdempotencyStore.class)
    JdbcIdempotencyStore idempotencyStore(
            JdbcClient jdbcClient,
            PlatformTransactionManager transactionManager,
            PlatformAdminJdbc platformAdminJdbc,
            JsonMapper jsonMapper) {
        return new JdbcIdempotencyStore(jdbcClient, transactionManager, platformAdminJdbc, jsonMapper);
    }

    @Bean
    IdempotencyCleanupJob idempotencyCleanupJob(IdempotencyStore store, Clock clock) {
        return new IdempotencyCleanupJob(store, clock);
    }
}
