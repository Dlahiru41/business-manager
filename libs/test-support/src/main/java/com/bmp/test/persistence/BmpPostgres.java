package com.bmp.test.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * One PostgreSQL 18 container per test JVM, bootstrapped with the same role script as {@code deploy/local}. Tests
 * connect as {@code bmp_app} (so row-level security is exercised), migrate as {@code bmp_migrator} and run platform
 * jobs as {@code bmp_platform_admin}. Tests stay independent by using a random tenant each, not by cleaning tables.
 */
public final class BmpPostgres {

    public static final String IMAGE = "postgres:18-alpine";
    public static final String DATABASE = "bmp";
    public static final String APP_USER = "bmp_app";
    public static final String MIGRATOR_USER = "bmp_migrator";
    public static final String PLATFORM_ADMIN_USER = "bmp_platform_admin";

    private static final String TEST_PASSWORD = "test-only";
    private static final PostgreSQLContainer CONTAINER = new PostgreSQLContainer(IMAGE)
            .withDatabaseName(DATABASE)
            .withUsername("postgres")
            .withPassword(TEST_PASSWORD)
            .withEnv("BMP_MIGRATOR_PASSWORD", TEST_PASSWORD)
            .withEnv("BMP_APP_PASSWORD", TEST_PASSWORD)
            .withEnv("BMP_PLATFORM_ADMIN_PASSWORD", TEST_PASSWORD)
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("init/01-roles.sh"), "/docker-entrypoint-initdb.d/01-roles.sh");

    private BmpPostgres() {}

    /** The started container (started on first use, stopped by Testcontainers when the JVM exits). */
    public static synchronized PostgreSQLContainer container() {
        if (!CONTAINER.isRunning()) {
            CONTAINER.start();
        }
        return CONTAINER;
    }

    public static String jdbcUrl() {
        return container().getJdbcUrl();
    }

    /** Spring properties pointing the application, the migrator and the platform-admin path at the container. */
    public static Map<String, String> springProperties() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("spring.datasource.url", jdbcUrl());
        properties.put("spring.datasource.username", APP_USER);
        properties.put("spring.datasource.password", TEST_PASSWORD);
        properties.put("bmp.persistence.migrator.username", MIGRATOR_USER);
        properties.put("bmp.persistence.migrator.password", TEST_PASSWORD);
        properties.put("bmp.persistence.platform-admin.username", PLATFORM_ADMIN_USER);
        properties.put("bmp.persistence.platform-admin.password", TEST_PASSWORD);
        return properties;
    }

    /** A superuser connection, for test set-up that must bypass the role model (e.g. tampering with a ledger). */
    public static Connection superuserConnection() throws SQLException {
        PostgreSQLContainer container = container();
        return DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    /** A connection as {@code role}, e.g. to prove a grant is missing. */
    public static Connection connectionAs(String role) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), role, TEST_PASSWORD);
    }
}
