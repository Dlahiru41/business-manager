package com.bmp.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.bmp.persistence.config.PersistenceDefaults;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.StandardEnvironment;

class ModuleMigratorTest {

    private static ModuleMigrator migrator(List<ModuleMigration> modules) {
        return new ModuleMigrator("jdbc:postgresql://unused/bmp", "u", "p", modules, false);
    }

    @Test
    void shouldRunPlatformFirstThenModulesInDependencyOrder() {
        ModuleMigrator migrator = migrator(List.of(
                new ModuleMigration("audit", 90), new ModuleMigration("tenant", 10), new ModuleMigration("iam", 20)));

        assertThat(migrator.migrations())
                .extracting(ModuleMigration::module)
                .containsExactly("platform", "tenant", "iam", "audit");
    }

    @Test
    void shouldRejectDuplicateAndMalformedModules() {
        assertThatIllegalStateException()
                .isThrownBy(
                        () -> migrator(List.of(new ModuleMigration("tenant", 1), new ModuleMigration("tenant", 2))));
        assertThatIllegalStateException().isThrownBy(() -> migrator(List.of(new ModuleMigration("platform", 1))));
        assertThatIllegalArgumentException().isThrownBy(() -> new ModuleMigration("Tenant", 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new ModuleMigration("tenant;drop", 1));
    }

    @Test
    void shouldSkipMigrationsWhenDisabled() {
        migrator(List.of(new ModuleMigration("tenant", 10))).afterPropertiesSet();
    }

    @Test
    void shouldConfigureEachModuleWithItsOwnSchemaAndLocation() {
        var flyway = migrator(List.of()).flyway(new ModuleMigration("tenant", 10));

        assertThat(flyway.getConfiguration().getSchemas()).containsExactly("tenant");
        assertThat(flyway.getConfiguration().getTable()).isEqualTo("flyway_schema_history");
        assertThat(flyway.getConfiguration().getLocations())
                .extracting(Object::toString)
                .containsExactly("classpath:db/migration/tenant");
    }

    @Test
    void shouldAddPlatformDefaultsWithLowestPrecedence() {
        StandardEnvironment environment = new StandardEnvironment();

        new PersistenceDefaults().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
        assertThat(environment.getProperty("spring.modulith.events.jdbc.schema"))
                .isEqualTo("platform");
        assertThat(environment.getPropertySources().stream().toList().getLast().getName())
                .isEqualTo("bmpPersistenceDefaults");
    }
}
