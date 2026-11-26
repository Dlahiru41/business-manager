package com.bmp.sync.adapter.out.persistence;

import com.bmp.persistence.jdbc.Jsonb;
import com.bmp.shared.context.TenantContext;
import com.bmp.sync.domain.model.DeviceState;
import com.bmp.sync.domain.port.SyncStates;
import com.bmp.sync.domain.service.ClockSkew;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link SyncStates} on {@code sync.device_state} and {@code sync.reported_gap}. */
@Repository
class JdbcSyncStates implements SyncStates {

    private final JdbcClient jdbc;

    JdbcSyncStates(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<DeviceState> device(UUID deviceId) {
        return jdbc.sql("""
                        SELECT device_id, register_id, last_bootstrap_at, last_upload_at, clock_skew_seconds, flags
                          FROM sync.device_state WHERE tenant_id = :tenantId AND device_id = :deviceId
                        """)
                .param("tenantId", TenantContext.require().value())
                .param("deviceId", deviceId)
                .query((rs, row) -> {
                    long skew = rs.getLong("clock_skew_seconds");
                    Long measured = rs.wasNull() ? null : skew;
                    return new DeviceState(
                            rs.getObject("device_id", UUID.class),
                            rs.getObject("register_id", UUID.class),
                            instant(rs.getTimestamp("last_bootstrap_at")),
                            instant(rs.getTimestamp("last_upload_at")),
                            measured,
                            flags(rs.getArray("flags")));
                })
                .optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void seen(UUID deviceId, UUID registerId, boolean bootstrap, @Nullable Long skew, Instant at) {
        boolean flagged = skew != null && ClockSkew.flagged(skew);
        jdbc.sql("""
                        INSERT INTO sync.device_state (tenant_id, device_id, register_id, last_bootstrap_at,
                                                       last_upload_at, clock_skew_seconds, flags)
                        VALUES (:tenantId, :deviceId, :registerId, :bootstrapAt, :uploadAt, :skew,
                                CASE WHEN :flagged THEN ARRAY['CLOCK_SKEW'] ELSE '{}'::text[] END)
                        ON CONFLICT (tenant_id, device_id) DO UPDATE SET
                            register_id = excluded.register_id,
                            last_bootstrap_at = coalesce(excluded.last_bootstrap_at, sync.device_state.last_bootstrap_at),
                            last_upload_at = coalesce(excluded.last_upload_at, sync.device_state.last_upload_at),
                            clock_skew_seconds = coalesce(excluded.clock_skew_seconds,
                                                          sync.device_state.clock_skew_seconds),
                            flags = CASE WHEN :measured THEN excluded.flags ELSE sync.device_state.flags END
                        """)
                .param("tenantId", TenantContext.require().value())
                .param("deviceId", deviceId)
                .param("registerId", registerId)
                .param("bootstrapAt", bootstrap ? Jsonb.utc(at) : null)
                .param("uploadAt", bootstrap ? null : Jsonb.utc(at))
                .param("skew", skew)
                .param("flagged", flagged)
                .param("measured", skew != null)
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Set<Long> reportedGaps(UUID registerId) {
        return new HashSet<>(
                jdbc.sql("SELECT sequence FROM sync.reported_gap WHERE tenant_id = :tenantId AND register_id = :r")
                        .param("tenantId", TenantContext.require().value())
                        .param("r", registerId)
                        .query((rs, row) -> rs.getLong("sequence"))
                        .list());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void reportGaps(UUID registerId, List<Long> sequences, Instant at) {
        for (long sequence : sequences) {
            jdbc.sql("""
                            INSERT INTO sync.reported_gap (tenant_id, register_id, sequence, reported_at)
                            VALUES (:tenantId, :r, :s, :at) ON CONFLICT DO NOTHING
                            """)
                    .param("tenantId", TenantContext.require().value())
                    .param("r", registerId)
                    .param("s", sequence)
                    .param("at", Jsonb.utc(at))
                    .update();
        }
    }

    private static List<String> flags(@Nullable Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

    private static @Nullable Instant instant(@Nullable Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
