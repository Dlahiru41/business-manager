package com.bmp.test.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The {@code RlsCoverageIT} check (06-persistence-and-data.md §2), usable by every module: every table with a
 * {@code tenant_id} column in the given schemas must have row-level security enabled <em>and forced</em> with at least
 * one policy, and ledger tables must not grant {@code UPDATE}/{@code DELETE} to {@code bmp_app}.
 */
public final class RlsCoverage {

    private static final String UNPROTECTED_TABLES =
            """
            SELECT n.nspname || '.' || c.relname AS table_name, c.relrowsecurity, c.relforcerowsecurity,
                   EXISTS (SELECT 1 FROM pg_policies p
                            WHERE p.schemaname = n.nspname AND p.tablename = c.relname) AS has_policy
              FROM pg_class c
              JOIN pg_namespace n ON n.oid = c.relnamespace
             WHERE c.relkind IN ('r', 'p')
               AND NOT c.relispartition
               AND n.nspname = ANY (?)
               AND EXISTS (SELECT 1 FROM information_schema.columns col
                            WHERE col.table_schema = n.nspname AND col.table_name = c.relname
                              AND col.column_name = 'tenant_id')
            """;

    private static final String LEDGER_WRITE_GRANTS =
            """
            SELECT table_schema || '.' || table_name || ':' || privilege_type
              FROM information_schema.role_table_grants
             WHERE grantee = 'bmp_app'
               AND privilege_type IN ('UPDATE', 'DELETE', 'TRUNCATE')
               AND table_schema || '.' || table_name = ANY (?)
            """;

    private RlsCoverage() {}

    /** Human-readable violations; empty when the schemas are fully covered. */
    public static List<String> violations(Collection<String> schemas, Collection<String> ledgerTables)
            throws SQLException {
        List<String> violations = new ArrayList<>();
        try (Connection connection = BmpPostgres.superuserConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(UNPROTECTED_TABLES)) {
                statement.setArray(1, textArray(connection, schemas));
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String table = rows.getString("table_name");
                        if (!rows.getBoolean("relrowsecurity")) {
                            violations.add(table + " has no row-level security");
                        }
                        if (!rows.getBoolean("relforcerowsecurity")) {
                            violations.add(table + " does not force row-level security");
                        }
                        if (!rows.getBoolean("has_policy")) {
                            violations.add(table + " has no policy");
                        }
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(LEDGER_WRITE_GRANTS)) {
                statement.setArray(1, textArray(connection, ledgerTables));
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        violations.add("ledger grants bmp_app " + rows.getString(1));
                    }
                }
            }
        }
        return violations;
    }

    /** Fails with every violation listed. */
    public static void assertCovered(Collection<String> schemas, Collection<String> ledgerTables) throws SQLException {
        assertThat(violations(schemas, ledgerTables)).as("row-level security coverage").isEmpty();
    }

    private static Array textArray(Connection connection, Collection<String> values) throws SQLException {
        return connection.createArrayOf("text", values.toArray());
    }
}
