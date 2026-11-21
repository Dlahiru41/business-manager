package com.bmp.persistence.migration;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Declares a module's migrations: {@code classpath:db/migration/<module>} into schema {@code <module>}, with the
 * module's own Flyway history table. Modules run in ascending {@code order}, which must follow the module dependency
 * chain (01-architecture-and-code-structure.md §2); the {@code platform} schema always runs first.
 *
 * @param module module name, equal to its schema name
 * @param order position in the migration sequence
 */
public record ModuleMigration(String module, int order) {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z_]{1,30}");

    public ModuleMigration {
        Objects.requireNonNull(module, "module");
        if (!NAME.matcher(module).matches()) {
            throw new IllegalArgumentException("Module names are lower-case identifiers: " + module);
        }
    }
}
