package com.bmp.sync.adapter.out.persistence;

import com.bmp.persistence.jdbc.Jsonb;
import com.bmp.shared.context.TenantContext;
import com.bmp.sync.domain.model.RecordOutcome;
import com.bmp.sync.domain.model.UploadRecordType;
import com.bmp.sync.domain.model.UploadStatus;
import com.bmp.sync.domain.port.UploadLog;
import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@link UploadLog} on {@code sync.upload_record}: INSERT and SELECT only. */
@Repository
class JdbcUploadLog implements UploadLog {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    JdbcUploadLog(JdbcClient jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<RecordOutcome> find(UploadRecordType type, UUID id) {
        return jdbc.sql("""
                        SELECT status, problem::text AS problem FROM sync.upload_record
                         WHERE tenant_id = :tenantId AND record_type = :type AND record_id = :id
                        """)
                .param("tenantId", TenantContext.require().value())
                .param("type", type.name())
                .param("id", id)
                .query((rs, row) -> {
                    String problem = rs.getString("problem");
                    return new RecordOutcome(
                            type,
                            id,
                            UploadStatus.valueOf(rs.getString("status")),
                            problem == null ? null : problem(json.readTree(problem)));
                })
                .optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, Boolean> rejectedById(Collection<UUID> ids) {
        Map<UUID, Boolean> rejected = new HashMap<>();
        if (ids.isEmpty()) {
            return rejected;
        }
        jdbc.sql("""
                        SELECT record_id, bool_or(status = 'REJECTED') AS rejected FROM sync.upload_record
                         WHERE tenant_id = :tenantId AND record_id = ANY (:ids) GROUP BY record_id
                        """)
                .param("tenantId", TenantContext.require().value())
                .param("ids", ids.toArray(UUID[]::new))
                .query((rs, row) -> rejected.put(rs.getObject("record_id", UUID.class), rs.getBoolean("rejected")))
                .list();
        return rejected;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean record(RecordOutcome outcome, UUID deviceId) {
        RecordOutcome.Problem problem = outcome.problem();
        return jdbc.sql("""
                        INSERT INTO sync.upload_record (tenant_id, record_type, record_id, device_id, status, problem,
                                                        received_at)
                        VALUES (:tenantId, :type, :id, :deviceId, :status, CAST(:problem AS jsonb), :at)
                        ON CONFLICT (tenant_id, record_type, record_id) DO NOTHING
                        """)
                        .param("tenantId", TenantContext.require().value())
                        .param("type", outcome.type().name())
                        .param("id", outcome.id())
                        .param("deviceId", deviceId)
                        .param("status", outcome.status().name())
                        .param("problem", problem == null ? null : json.writeValueAsString(problem))
                        .param("at", Jsonb.utc(clock.instant()))
                        .update()
                == 1;
    }

    private static RecordOutcome.Problem problem(JsonNode node) {
        JsonNode detail = node.path("detail");
        return new RecordOutcome.Problem(
                node.path("type").asString(),
                node.path("code").asString(),
                node.path("title").asString(),
                node.path("status").asInt(),
                detail.isMissingNode() || detail.isNull() ? null : detail.asString());
    }
}
