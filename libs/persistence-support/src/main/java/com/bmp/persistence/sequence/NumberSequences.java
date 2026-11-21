package com.bmp.persistence.sequence;

import com.bmp.shared.context.TenantContext;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Allocates document numbers (PO, GRN, credit note) from a module's own {@code <schema>.number_sequence} table in the
 * <em>same transaction</em> as the document (ADR-012). {@code platform.next_number} runs {@code UPDATE ... RETURNING},
 * which locks the row: concurrent allocations serialize and a rolled-back document gives its number back. Never
 * PostgreSQL sequences per tenant. Row-level security applies, so a tenant only ever touches its own sequences.
 *
 * <p>Each module creates the table in its own schema:
 *
 * <pre>{@code
 * number_sequence (tenant_id uuid, scope text, prefix text, next_value bigint, PK (tenant_id, scope))
 * }</pre>
 */
public class NumberSequences {

    private static final Pattern SCHEMA = Pattern.compile("[a-z][a-z_]{1,30}");

    private final JdbcClient jdbc;
    private final String table;

    /**
     * @param schema the owning module's schema, e.g. {@code procurement}
     */
    public NumberSequences(JdbcClient jdbc, String schema) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalArgumentException("Invalid schema name: " + schema);
        }
        this.table = schema + ".number_sequence";
    }

    /** The next number of {@code scope} for the current tenant; a new scope starts at 1. */
    @Transactional(propagation = Propagation.MANDATORY)
    public long next(String scope) {
        return jdbc.sql("SELECT platform.next_number(CAST(:table AS regclass), :tenantId, :scope)")
                .param("table", table)
                .param("tenantId", TenantContext.require().value())
                .param("scope", scope)
                .query(Long.class)
                .single();
    }
}
