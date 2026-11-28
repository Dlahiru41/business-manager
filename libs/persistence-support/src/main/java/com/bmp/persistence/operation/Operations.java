package com.bmp.persistence.operation;

import com.bmp.persistence.jdbc.Jsonb;
import com.bmp.shared.context.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The tenant's background operations: PENDING when accepted, RUNNING, then SUCCEEDED with where to read the result or
 * FAILED with a Problem. Modules record them in their own transactions; {@code GET /api/v1/operations/{id}} reads them.
 */
public class Operations {

    private final JdbcClient jdbc;
    private final Clock clock;

    public Operations(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** An operation as stored; {@code problem} is Problem Details JSON. */
    public record Operation(
            UUID id,
            String type,
            String status,
            int progress,
            @Nullable String resultRef,
            @Nullable String problem,
            Instant createdAt,
            Instant updatedAt) {

        public boolean isFinished() {
            return status.equals("SUCCEEDED") || status.equals("FAILED");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void create(UUID id, String type) {
        var now = Jsonb.utc(clock.instant());
        jdbc.sql("""
                        INSERT INTO platform.operation (id, tenant_id, type, status, progress, created_at, updated_at)
                        VALUES (:id, :tenantId, :type, 'PENDING', 0, :now, :now)
                        """)
                .param("id", id)
                .param("tenantId", TenantContext.require().value())
                .param("type", type)
                .param("now", now)
                .update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void running(UUID id, int progress) {
        update(id, "RUNNING", progress, null, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void succeeded(UUID id, @Nullable String resultRef) {
        update(id, "SUCCEEDED", 100, resultRef, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void failed(UUID id, String problemJson) {
        update(id, "FAILED", 100, null, problemJson);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Operation> find(UUID id) {
        return jdbc.sql("""
                        SELECT id, type, status, progress, result_ref, problem::text AS problem, created_at, updated_at
                          FROM platform.operation WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", TenantContext.require().value())
                .param("id", id)
                .query(Operations::operation)
                .optional();
    }

    private void update(UUID id, String status, int progress, @Nullable String resultRef, @Nullable String problem) {
        jdbc.sql("""
                        UPDATE platform.operation
                           SET status = :status, progress = :progress, result_ref = coalesce(:resultRef, result_ref),
                               problem = coalesce(CAST(:problem AS jsonb), problem), updated_at = :now,
                               version = version + 1
                         WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("status", status)
                .param("progress", progress)
                .param("resultRef", resultRef)
                .param("problem", problem)
                .param("now", Jsonb.utc(clock.instant()))
                .param("tenantId", TenantContext.require().value())
                .param("id", id)
                .update();
    }

    private static Operation operation(ResultSet rs, int row) throws SQLException {
        return new Operation(
                rs.getObject("id", UUID.class),
                rs.getString("type"),
                rs.getString("status"),
                rs.getInt("progress"),
                rs.getString("result_ref"),
                rs.getString("problem"),
                Jsonb.instant(Objects.requireNonNull(rs.getTimestamp("created_at"))),
                Jsonb.instant(Objects.requireNonNull(rs.getTimestamp("updated_at"))));
    }
}
