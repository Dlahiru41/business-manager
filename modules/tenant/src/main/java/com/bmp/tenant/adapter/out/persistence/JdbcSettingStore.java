package com.bmp.tenant.adapter.out.persistence;

import com.bmp.persistence.jdbc.Jsonb;
import com.bmp.shared.context.ActorContext;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.IdGenerator;
import com.bmp.shared.id.UserId;
import com.bmp.tenant.domain.model.SettingGroup;
import com.bmp.tenant.domain.port.SettingStore;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link SettingStore} on {@code tenant.setting} (current values) and {@code tenant.setting_history} (append-only). */
@Repository
class JdbcSettingStore implements SettingStore {

    private final JdbcClient jdbc;
    private final IdGenerator ids;
    private final Clock clock;

    JdbcSettingStore(JdbcClient jdbc, IdGenerator ids, Clock clock) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, StoredValue> load(SettingGroup group) {
        Map<String, StoredValue> values = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT key, value::text AS value, version FROM tenant.setting
                         WHERE tenant_id = :tenantId AND group_name = :group
                        """)
                .param("tenantId", tenant())
                .param("group", group.code())
                .query((rs, row) ->
                        Map.entry(rs.getString("key"), new StoredValue(rs.getString("value"), rs.getLong("version"))))
                .list()
                .forEach(entry -> values.put(entry.getKey(), entry.getValue()));
        return values;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock() {
        jdbc.sql("SELECT id FROM tenant.tenant WHERE id = :tenantId FOR UPDATE")
                .param("tenantId", tenant())
                .query(UUID.class)
                .optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void write(SettingGroup group, String key, String json, @Nullable String previousJson, long version) {
        var now = Jsonb.utc(clock.instant());
        UserId actor = ActorContext.currentOrSystem().userId();
        UUID actorId = actor != null ? actor.value() : null;
        jdbc.sql("""
                        INSERT INTO tenant.setting (tenant_id, group_name, key, value, version, updated_at, updated_by)
                        VALUES (:tenantId, :group, :key, :value, :version, :now, :actor)
                        ON CONFLICT (tenant_id, group_name, key) DO UPDATE
                           SET value = EXCLUDED.value, version = EXCLUDED.version,
                               updated_at = EXCLUDED.updated_at, updated_by = EXCLUDED.updated_by
                        """)
                .param("tenantId", tenant())
                .param("group", group.code())
                .param("key", key)
                .param("value", Jsonb.of(json))
                .param("version", version)
                .param("now", now)
                .param("actor", actorId)
                .update();
        jdbc.sql("""
                        INSERT INTO tenant.setting_history (id, tenant_id, group_name, key, old_value, new_value, version,
                                                            changed_at, changed_by)
                        VALUES (:id, :tenantId, :group, :key, :oldValue, :newValue, :version, :now, :actor)
                        """)
                .param("id", ids.newUuid())
                .param("tenantId", tenant())
                .param("group", group.code())
                .param("key", key)
                .param("oldValue", Jsonb.of(previousJson))
                .param("newValue", Jsonb.of(json))
                .param("version", version)
                .param("now", now)
                .param("actor", actorId)
                .update();
    }

    private static UUID tenant() {
        return TenantContext.require().value();
    }
}
