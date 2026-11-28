package com.bmp.reporting.adapter.out.persistence;

import com.bmp.persistence.jdbc.Jsonb;
import com.bmp.reporting.domain.model.Fact;
import com.bmp.reporting.domain.port.EventLog;
import com.bmp.shared.context.TenantContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** {@link EventLog} on {@code reporting.event_log}: facts as JSON with their record name, insert only. */
@Repository
class JdbcEventLog implements EventLog {

    private static final Map<String, Class<?>> TYPES = Arrays.stream(Fact.class.getPermittedSubclasses())
            .collect(Collectors.toMap(Class::getSimpleName, Function.identity()));

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    JdbcEventLog(JdbcClient jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean append(UUID eventId, Instant occurredAt, Fact fact) {
        return jdbc.sql("""
                        INSERT INTO reporting.event_log (tenant_id, event_id, fact_type, payload, occurred_at, recorded_at)
                        VALUES (:tenantId, :eventId, :type, :payload, :occurredAt, :recordedAt)
                        ON CONFLICT (tenant_id, event_id) DO NOTHING
                        """)
                        .param("tenantId", TenantContext.require().value())
                        .param("eventId", eventId)
                        .param("type", fact.getClass().getSimpleName())
                        .param("payload", Jsonb.of(json.writeValueAsString(fact)))
                        .param("occurredAt", Jsonb.utc(occurredAt))
                        .param("recordedAt", Jsonb.utc(clock.instant()))
                        .update()
                == 1;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Entry> read(int offset, int limit) {
        return jdbc.sql("""
                        SELECT event_id, fact_type, payload::text AS payload FROM reporting.event_log
                         WHERE tenant_id = :tenantId ORDER BY seq OFFSET :offset LIMIT :limit
                        """)
                .param("tenantId", TenantContext.require().value())
                .param("offset", offset)
                .param("limit", limit)
                .query((rs, row) -> new Entry(rs.getObject("event_id", UUID.class), (Fact)
                        json.readValue(rs.getString("payload"), TYPES.get(rs.getString("fact_type")))))
                .list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Instant> lastRecordedAt() {
        return jdbc.sql("SELECT max(recorded_at) AS at FROM reporting.event_log WHERE tenant_id = :tenantId")
                .param("tenantId", TenantContext.require().value())
                .query((rs, row) -> {
                    Timestamp at = rs.getTimestamp("at");
                    return at == null ? null : Jsonb.instant(at);
                })
                .optional();
    }
}
