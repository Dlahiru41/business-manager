package com.bmp.persistence.config;

import com.bmp.persistence.event.InboxGuard;
import com.bmp.persistence.jdbc.PlatformAdminJdbc;
import com.bmp.persistence.migration.ModuleMigration;
import com.bmp.persistence.migration.ModuleMigrator;
import com.bmp.persistence.tenant.ContextTenantIdentifierResolver;
import com.bmp.persistence.tenant.TenantAwareJpaTransactionManager;
import com.bmp.shared.id.IdGenerator;
import java.time.Clock;
import java.util.List;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.hibernate.cfg.AvailableSettings;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.jpa.autoconfigure.EntityManagerFactoryBuilderCustomizer;
import org.springframework.boot.jpa.autoconfigure.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Wires tenant isolation (RLS transaction hook and Hibernate tenant resolver), per-module migrations, the audited
 * platform-admin path, the inbox and ShedLock. Runs before Boot's JPA configuration so its transaction manager wins.
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class, before = HibernateJpaAutoConfiguration.class)
@EnableConfigurationProperties(PersistenceProperties.class)
public class PersistenceAutoConfiguration {

    /** Mapping file registering the default auditing entity listener. */
    public static final String ORM_XML = "META-INF/bmp-orm.xml";

    private static final int JDBC_BATCH_SIZE = 50;

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    IdGenerator idGenerator() {
        return IdGenerator.uuidV7();
    }

    /** Database-backed beans; absent when no database is configured ({@code spring.datasource.url}). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "spring.datasource", name = "url")
    @EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
    @Import(EventInfrastructureConfiguration.class)
    static class DatabaseConfiguration {

        /** JPA must wait for the schema migrations. */
        @Bean
        static EntityManagerFactoryDependsOnPostProcessor entityManagerFactoryDependsOnMigrations() {
            return new EntityManagerFactoryDependsOnPostProcessor(ModuleMigrator.class);
        }

        @Bean
        ModuleMigrator moduleMigrator(
                DataSourceProperties dataSource, PersistenceProperties properties, List<ModuleMigration> modules) {
            return new ModuleMigrator(
                    dataSource.determineUrl(),
                    properties.migrator().username(),
                    properties.migrator().password(),
                    modules,
                    properties.migrateOnStartup());
        }

        @Bean
        PlatformTransactionManager transactionManager(ObjectProvider<TransactionManagerCustomizers> customizers) {
            TenantAwareJpaTransactionManager transactionManager = new TenantAwareJpaTransactionManager();
            customizers.ifAvailable(c -> c.customize(transactionManager));
            return transactionManager;
        }

        @Bean
        HibernatePropertiesCustomizer bmpHibernateProperties() {
            return properties -> {
                properties.put(
                        AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, new ContextTenantIdentifierResolver());
                properties.put(AvailableSettings.STATEMENT_BATCH_SIZE, JDBC_BATCH_SIZE);
                properties.put(AvailableSettings.ORDER_INSERTS, true);
                properties.put(AvailableSettings.ORDER_UPDATES, true);
                properties.put(AvailableSettings.JDBC_TIME_ZONE, "UTC");
            };
        }

        @Bean
        EntityManagerFactoryBuilderCustomizer bmpOrmMapping() {
            return builder -> builder.addPersistenceUnitPostProcessors(unit -> unit.addMappingFileName(ORM_XML));
        }

        @Bean
        PlatformAdminJdbc platformAdminJdbc(DataSourceProperties dataSource, PersistenceProperties properties) {
            return new PlatformAdminJdbc(
                    dataSource.determineUrl(),
                    properties.platformAdmin().username(),
                    properties.platformAdmin().password());
        }

        @Bean
        InboxGuard inboxGuard(JdbcClient jdbcClient, Clock clock) {
            return new InboxGuard(jdbcClient, clock);
        }

        @Bean
        LockProvider lockProvider(DataSource dataSource) {
            return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                    .withJdbcTemplate(new JdbcTemplate(dataSource))
                    .withTableName("platform.shedlock")
                    .usingDbTime()
                    .build());
        }
    }
}
