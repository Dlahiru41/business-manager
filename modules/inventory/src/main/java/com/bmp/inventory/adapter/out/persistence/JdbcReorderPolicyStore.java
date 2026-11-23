package com.bmp.inventory.adapter.out.persistence;

import com.bmp.inventory.domain.model.ReorderPolicy;
import com.bmp.inventory.domain.port.ReorderPolicyStore;
import com.bmp.persistence.jdbc.Jsonb;
import com.bmp.shared.context.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link ReorderPolicyStore} on {@code inventory.reorder_policy}. */
@Repository
class JdbcReorderPolicyStore implements ReorderPolicyStore {

    private static final String COLUMNS =
            "variant_id, location_id, reorder_level, reorder_qty, preferred_supplier_id, low_flag, version";

    private final JdbcClient jdbc;
    private final Clock clock;

    JdbcReorderPolicyStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ReorderPolicy> lock(UUID variantId, UUID locationId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM inventory.reorder_policy
                         WHERE tenant_id = :tenantId AND variant_id = :variantId AND location_id = :locationId
                         FOR UPDATE
                        """)
                .param("tenantId", TenantContext.require().value())
                .param("variantId", variantId)
                .param("locationId", locationId)
                .query(JdbcReorderPolicyStore::policy)
                .optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ReorderPolicy save(ReorderPolicy policy) {
        jdbc.sql("""
                        INSERT INTO inventory.reorder_policy
                               (tenant_id, variant_id, location_id, reorder_level, reorder_qty, preferred_supplier_id,
                                low_flag, created_at, updated_at)
                        VALUES (:tenantId, :variantId, :locationId, :level, :qty, :supplierId, :low, :now, :now)
                        ON CONFLICT (tenant_id, variant_id, location_id) DO UPDATE
                           SET reorder_level = EXCLUDED.reorder_level, reorder_qty = EXCLUDED.reorder_qty,
                               preferred_supplier_id = EXCLUDED.preferred_supplier_id, low_flag = EXCLUDED.low_flag,
                               version = inventory.reorder_policy.version + 1, updated_at = EXCLUDED.updated_at
                        """)
                .param("tenantId", TenantContext.require().value())
                .param("variantId", policy.variantId())
                .param("locationId", policy.locationId())
                .param("level", policy.reorderLevel())
                .param("qty", policy.reorderQty())
                .param("supplierId", policy.preferredSupplierId())
                .param("low", policy.lowFlag())
                .param("now", Jsonb.utc(clock.instant()))
                .update();
        return lock(policy.variantId(), policy.locationId()).orElseThrow();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void flag(UUID variantId, UUID locationId, boolean low) {
        jdbc.sql("""
                        UPDATE inventory.reorder_policy SET low_flag = :low, updated_at = :now
                         WHERE tenant_id = :tenantId AND variant_id = :variantId AND location_id = :locationId
                        """)
                .param("low", low)
                .param("now", Jsonb.utc(clock.instant()))
                .param("tenantId", TenantContext.require().value())
                .param("variantId", variantId)
                .param("locationId", locationId)
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ReorderPolicy> low(@Nullable UUID locationId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM inventory.reorder_policy p
                         WHERE p.tenant_id = :tenantId
                           AND (CAST(:locationId AS uuid) IS NULL OR p.location_id = :locationId)
                           AND (SELECT coalesce(sum(b.on_hand - b.reserved), 0) FROM inventory.stock_balance b
                                 WHERE b.tenant_id = p.tenant_id AND b.variant_id = p.variant_id
                                   AND b.location_id = p.location_id) <= p.reorder_level
                         ORDER BY p.variant_id, p.location_id
                        """)
                .param("tenantId", TenantContext.require().value())
                .param("locationId", locationId)
                .query(JdbcReorderPolicyStore::policy)
                .list();
    }

    private static ReorderPolicy policy(ResultSet rs, int row) throws SQLException {
        return new ReorderPolicy(
                rs.getObject("variant_id", UUID.class),
                rs.getObject("location_id", UUID.class),
                rs.getBigDecimal("reorder_level"),
                rs.getBigDecimal("reorder_qty"),
                rs.getObject("preferred_supplier_id", UUID.class),
                rs.getBoolean("low_flag"),
                rs.getLong("version"));
    }
}
