package com.bmp.persistence.migration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

/**
 * Runs the {@code platform} migrations and then every module's migrations, each with its own Flyway instance, schema
 * and history table, as the schema-owning migrator role. Runs before JPA starts so mappings meet the final schema.
 */
public class ModuleMigrator implements InitializingBean {

    /** Platform infrastructure schema (idempotency, inbox, ShedLock, event publications). */
    public static final ModuleMigration PLATFORM = new ModuleMigration("platform", Integer.MIN_VALUE);

    private static final Logger log = LoggerFactory.getLogger(ModuleMigrator.class);

    private final String url;
    private final String username;
    private final String password;
    private final List<ModuleMigration> migrations;
    private final boolean enabled;

    public ModuleMigrator(
            String url, String username, String password, List<ModuleMigration> modules, boolean enabled) {
        this.url = url;
        this.username = username;
        this.password = password;
        this.enabled = enabled;
        this.migrations = ordered(modules);
    }

    @Override
    public void afterPropertiesSet() {
        if (!enabled) {
            log.info("Module migrations disabled (bmp.persistence.migrate-on-startup=false)");
            return;
        }
        for (ModuleMigration migration : migrations) {
            MigrateResult result = flyway(migration).migrate();
            log.atInfo()
                    .addKeyValue("module", migration.module())
                    .addKeyValue("applied", result.migrationsExecuted)
                    .addKeyValue("schemaVersion", result.targetSchemaVersion)
                    .log("Migrated schema {} ({} migrations applied)", migration.module(), result.migrationsExecuted);
        }
    }

    /** The modules in the order they are migrated (platform first). */
    public List<ModuleMigration> migrations() {
        return migrations;
    }

    Flyway flyway(ModuleMigration migration) {
        return Flyway.configure()
                .dataSource(url, username, password)
                .schemas(migration.module())
                .defaultSchema(migration.module())
                .createSchemas(true)
                .table("flyway_schema_history")
                .locations("classpath:db/migration/" + migration.module())
                .validateMigrationNaming(true)
                .failOnMissingLocations(true)
                .load();
    }

    private static List<ModuleMigration> ordered(List<ModuleMigration> modules) {
        Set<String> names = new HashSet<>();
        List<ModuleMigration> ordered = new ArrayList<>();
        ordered.add(PLATFORM);
        names.add(PLATFORM.module());
        modules.stream()
                .sorted(Comparator.comparingInt(ModuleMigration::order).thenComparing(ModuleMigration::module))
                .forEach(m -> {
                    if (!names.add(m.module())) {
                        throw new IllegalStateException("Module migration declared twice: " + m.module());
                    }
                    ordered.add(m);
                });
        return List.copyOf(ordered);
    }
}
