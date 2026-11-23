package com.bmp.inventory.adapter.out.persistence;

import com.bmp.inventory.domain.model.RunLine;
import com.bmp.inventory.domain.model.TransformationRun;
import com.bmp.inventory.domain.port.TransformationRunStore;
import com.bmp.persistence.jdbc.Jsonb;
import com.bmp.shared.context.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** {@link TransformationRunStore} on {@code inventory.transformation_run}: inserts only. */
@Repository
class JdbcTransformationRunStore implements TransformationRunStore {

    private static final TypeReference<List<RunLine>> LINES = new TypeReference<>() {};
    private static final String COLUMNS = """
            id, definition_id, def_version, kind, location_id, runs, inputs::text AS inputs, outputs::text AS outputs,
            yield_variance_qty, total_cost, currency, note, occurred_at, actor_id, negative_stock""";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    JdbcTransformationRunStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(TransformationRun run) {
        jdbc.sql("""
                        INSERT INTO inventory.transformation_run
                               (tenant_id, id, definition_id, def_version, kind, location_id, runs, inputs, outputs,
                                yield_variance_qty, total_cost, currency, note, occurred_at, actor_id, negative_stock)
                        VALUES (:tenantId, :id, :definitionId, :defVersion, :kind, :locationId, :runs, :inputs, :outputs,
                                :variance, :totalCost, :currency, :note, :occurredAt, :actorId, :negative)
                        """)
                .param("tenantId", TenantContext.require().value())
                .param("id", run.id())
                .param("definitionId", run.definitionId())
                .param("defVersion", run.defVersion())
                .param("kind", run.kind())
                .param("locationId", run.locationId())
                .param("runs", run.runs())
                .param("inputs", Jsonb.of(json.writeValueAsString(run.inputs())))
                .param("outputs", Jsonb.of(json.writeValueAsString(run.outputs())))
                .param("variance", run.yieldVarianceQty())
                .param("totalCost", run.totalCost())
                .param("currency", run.currency())
                .param("note", run.note())
                .param("occurredAt", Jsonb.utc(run.occurredAt()))
                .param("actorId", run.actorId())
                .param("negative", run.negativeStock())
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<TransformationRun> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM inventory.transformation_run WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", TenantContext.require().value())
                .param("id", id)
                .query(this::run)
                .optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<TransformationRun> findPage(
            @Nullable UUID definitionId, @Nullable UUID locationId, @Nullable UUID beforeId, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM inventory.transformation_run
                         WHERE tenant_id = :tenantId
                           AND (CAST(:definitionId AS uuid) IS NULL OR definition_id = :definitionId)
                           AND (CAST(:locationId AS uuid) IS NULL OR location_id = :locationId)
                           AND (CAST(:beforeId AS uuid) IS NULL OR id < :beforeId)
                         ORDER BY id DESC
                         LIMIT :limit
                        """)
                .param("tenantId", TenantContext.require().value())
                .param("definitionId", definitionId)
                .param("locationId", locationId)
                .param("beforeId", beforeId)
                .param("limit", limit)
                .query(this::run)
                .list();
    }

    private TransformationRun run(ResultSet rs, int row) throws SQLException {
        return new TransformationRun(
                rs.getObject("id", UUID.class),
                rs.getObject("definition_id", UUID.class),
                rs.getInt("def_version"),
                rs.getString("kind"),
                rs.getObject("location_id", UUID.class),
                rs.getBigDecimal("runs"),
                json.readValue(rs.getString("inputs"), LINES),
                json.readValue(rs.getString("outputs"), LINES),
                rs.getBigDecimal("yield_variance_qty"),
                rs.getBigDecimal("total_cost"),
                rs.getString("currency"),
                rs.getString("note"),
                Jsonb.instant(Objects.requireNonNull(rs.getTimestamp("occurred_at"))),
                rs.getObject("actor_id", UUID.class),
                rs.getBoolean("negative_stock"));
    }
}
