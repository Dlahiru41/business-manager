package com.bmp.audit.adapter.out.persistence;

import com.bmp.audit.api.AuditEntry.AuditSource;
import com.bmp.audit.domain.model.AuditRecord;
import com.bmp.audit.domain.model.AuditRecord.ChainHead;
import com.bmp.audit.domain.port.AuditLedger;
import com.bmp.persistence.jdbc.Jsonb;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.DeviceId;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Ledger writes with {@code JdbcClient}: INSERT and SELECT only (06-persistence-and-data.md §2); RLS scopes reads. */
@Repository
class JdbcAuditLedger implements AuditLedger {

    /** Namespace of the advisory lock keys, so audit locks do not collide with other uses. */
    private static final long LOCK_NAMESPACE = 0x6175_6469_74L;

    private static final String COLUMNS = """
            id, tenant_id, sequence, occurred_at, recorded_at, source, action, resource_type, resource_id, outcome,
            actor_id, device_id, platform_admin, before_hash, after_hash, ip, user_agent, request_id, event_id,
            prev_hash, hash
            """;

    private final JdbcClient jdbc;

    JdbcAuditLedger(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockChain() {
        UUID tenant = TenantContext.require().value();
        long key = LOCK_NAMESPACE ^ tenant.getMostSignificantBits() ^ tenant.getLeastSignificantBits();
        jdbc.sql("SELECT pg_advisory_xact_lock(:key)")
                .param("key", key)
                .query((rs, row) -> row)
                .single();
    }

    @Override
    public ChainHead head() {
        return jdbc.sql("""
                        SELECT sequence, hash FROM audit.audit_record
                         WHERE tenant_id = :tenant
                         ORDER BY sequence DESC
                         LIMIT 1
                        """)
                .param("tenant", TenantContext.require().value())
                .query((rs, row) -> new ChainHead(rs.getLong("sequence"), rs.getString("hash")))
                .optional()
                .orElse(ChainHead.EMPTY);
    }

    @Override
    public void append(AuditRecord record) {
        UserId actor = record.actorId();
        DeviceId device = record.deviceId();
        jdbc.sql("""
                        INSERT INTO audit.audit_record (%s)
                        VALUES (:id, :tenantId, :sequence, :occurredAt, :recordedAt, :source, :action, :resourceType,
                                :resourceId, :outcome, :actorId, :deviceId, :platformAdmin, :beforeHash, :afterHash,
                                :ip, :userAgent, :requestId, :eventId, :prevHash, :hash)
                        """.formatted(COLUMNS))
                .param("id", record.id())
                .param("tenantId", record.tenantId().value())
                .param("sequence", record.sequence())
                .param("occurredAt", Jsonb.utc(record.occurredAt()))
                .param("recordedAt", Jsonb.utc(record.recordedAt()))
                .param("source", record.source().name())
                .param("action", record.action())
                .param("resourceType", record.resourceType())
                .param("resourceId", record.resourceId())
                .param("outcome", record.outcome())
                .param("actorId", actor != null ? actor.value() : null)
                .param("deviceId", device != null ? device.value() : null)
                .param("platformAdmin", record.platformAdmin())
                .param("beforeHash", record.beforeHash())
                .param("afterHash", record.afterHash())
                .param("ip", record.ip())
                .param("userAgent", record.userAgent())
                .param("requestId", record.requestId())
                .param("eventId", record.eventId())
                .param("prevHash", record.prevHash())
                .param("hash", record.hash())
                .update();
    }

    @Override
    public List<AuditRecord> find(
            @Nullable String resourceType,
            @Nullable String resourceId,
            @Nullable Instant from,
            @Nullable Instant to,
            long beforeSequence,
            int limit) {
        return jdbc.sql("""
                        SELECT %s FROM audit.audit_record
                         WHERE tenant_id = :tenant
                           AND sequence < :before
                           AND (CAST(:resourceType AS text) IS NULL OR resource_type = :resourceType)
                           AND (CAST(:resourceId AS text) IS NULL OR resource_id = :resourceId)
                           AND (CAST(:from AS timestamptz) IS NULL OR occurred_at >= :from)
                           AND (CAST(:to AS timestamptz) IS NULL OR occurred_at < :to)
                         ORDER BY sequence DESC
                         LIMIT :limit
                        """.formatted(COLUMNS))
                .param("tenant", TenantContext.require().value())
                .param("before", beforeSequence)
                .param("resourceType", resourceType)
                .param("resourceId", resourceId)
                .param("from", timestamp(from))
                .param("to", timestamp(to))
                .param("limit", limit)
                .query(JdbcAuditLedger::map)
                .list();
    }

    @Override
    public List<AuditRecord> chain(long afterSequence, int limit) {
        return jdbc.sql("""
                        SELECT %s FROM audit.audit_record
                         WHERE tenant_id = :tenant
                           AND sequence > :after
                         ORDER BY sequence
                         LIMIT :limit
                        """.formatted(COLUMNS))
                .param("tenant", TenantContext.require().value())
                .param("after", afterSequence)
                .param("limit", limit)
                .query(JdbcAuditLedger::map)
                .list();
    }

    private static @Nullable OffsetDateTime timestamp(@Nullable Instant instant) {
        return instant != null ? Jsonb.utc(instant) : null;
    }

    private static AuditRecord map(ResultSet rs, int row) throws SQLException {
        UUID actor = rs.getObject("actor_id", UUID.class);
        UUID device = rs.getObject("device_id", UUID.class);
        int outcome = rs.getInt("outcome");
        Integer outcomeOrNull = rs.wasNull() ? null : outcome;
        return new AuditRecord(
                rs.getObject("id", UUID.class),
                TenantId.of(rs.getObject("tenant_id", UUID.class)),
                rs.getLong("sequence"),
                instant(rs.getTimestamp("occurred_at")),
                instant(rs.getTimestamp("recorded_at")),
                AuditSource.valueOf(rs.getString("source")),
                rs.getString("action"),
                rs.getString("resource_type"),
                rs.getString("resource_id"),
                outcomeOrNull,
                actor != null ? UserId.of(actor) : null,
                device != null ? DeviceId.of(device) : null,
                rs.getBoolean("platform_admin"),
                rs.getString("before_hash"),
                rs.getString("after_hash"),
                rs.getString("ip"),
                rs.getString("user_agent"),
                rs.getString("request_id"),
                rs.getObject("event_id", UUID.class),
                rs.getString("prev_hash"),
                rs.getString("hash"));
    }

    private static Instant instant(@Nullable Timestamp timestamp) {
        return Jsonb.instant(Objects.requireNonNull(timestamp, "timestamp"));
    }
}
