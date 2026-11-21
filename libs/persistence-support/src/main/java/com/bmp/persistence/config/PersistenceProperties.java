package com.bmp.persistence.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Persistence settings ({@code bmp.persistence.*}). The application itself connects as {@code bmp_app}
 * ({@code spring.datasource.*}); migrations run as the schema owner and cross-tenant platform jobs as a
 * {@code BYPASSRLS} role (06-persistence-and-data.md §1, §4).
 *
 * @param migrateOnStartup run module migrations when the application starts (the migrator job does it in prod)
 * @param migrator credentials of the schema owner role used by Flyway
 * @param platformAdmin credentials of the audited cross-tenant role
 */
@Validated
@ConfigurationProperties("bmp.persistence")
public record PersistenceProperties(
        @DefaultValue("true") boolean migrateOnStartup,
        @Valid @DefaultValue Credentials migrator,
        @Valid @DefaultValue Credentials platformAdmin) {

    /**
     * Database credentials; URL is {@code spring.datasource.url}.
     *
     * @param username role name
     * @param password role password (from the environment, never committed)
     */
    public record Credentials(
            @DefaultValue("unset") @NotBlank String username,
            @DefaultValue("unset") String password) {
        @Override
        public String toString() {
            return "Credentials[username=" + username + ", password=***]";
        }
    }
}
