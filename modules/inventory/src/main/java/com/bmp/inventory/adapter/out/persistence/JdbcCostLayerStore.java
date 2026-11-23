package com.bmp.inventory.adapter.out.persistence;

import com.bmp.inventory.domain.model.BalanceKey;
import com.bmp.inventory.domain.model.CostLayer;
import com.bmp.inventory.domain.port.CostLayerStore;
import com.bmp.persistence.jdbc.Jsonb;
import com.bmp.shared.context.TenantContext;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link CostLayerStore} on {@code inventory.cost_layer}. */
@Repository
class JdbcCostLayerStore implements CostLayerStore {

    private final JdbcClient jdbc;

    JdbcCostLayerStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<BalanceKey, List<CostLayer>> load(Collection<BalanceKey> keys) {
        Map<BalanceKey, List<CostLayer>> layers = new HashMap<>();
        for (BalanceKey key : keys) {
            layers.put(
                    key,
                    jdbc.sql("""
                            SELECT id, qty_remaining, value_remaining, unit_cost, received_at FROM inventory.cost_layer
                             WHERE tenant_id = :tenantId AND variant_id = :variantId AND location_id = :locationId
                               AND stock_state = :state AND batch_id = :batchId AND qty_remaining > 0
                             ORDER BY id
                            """)
                            .param("tenantId", TenantContext.require().value())
                            .param("variantId", key.variantId())
                            .param("locationId", key.locationId())
                            .param("state", key.stockState())
                            .param("batchId", key.batchId())
                            .query((rs, row) -> new CostLayer(
                                    rs.getObject("id", UUID.class),
                                    rs.getBigDecimal("qty_remaining"),
                                    rs.getBigDecimal("value_remaining"),
                                    rs.getBigDecimal("unit_cost"),
                                    Jsonb.instant(Objects.requireNonNull(rs.getTimestamp("received_at")))))
                            .list());
        }
        return layers;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void save(BalanceKey key, List<CostLayer> before, List<CostLayer> after) {
        Map<UUID, CostLayer> remaining = new HashMap<>();
        after.forEach(l -> remaining.put(l.id(), l));
        UUID tenant = TenantContext.require().value();
        for (CostLayer old : before) {
            CostLayer now = remaining.remove(old.id());
            if (now == null || now.qtyRemaining().compareTo(old.qtyRemaining()) != 0) {
                jdbc.sql("""
                                UPDATE inventory.cost_layer SET qty_remaining = :qty, value_remaining = :value
                                 WHERE tenant_id = :tenantId AND id = :id
                                """)
                        .param("qty", now == null ? java.math.BigDecimal.ZERO : now.qtyRemaining())
                        .param("value", now == null ? java.math.BigDecimal.ZERO : now.valueRemaining())
                        .param("tenantId", tenant)
                        .param("id", old.id())
                        .update();
            }
        }
        for (CostLayer added : remaining.values()) {
            jdbc.sql("""
                            INSERT INTO inventory.cost_layer
                                   (tenant_id, id, variant_id, location_id, stock_state, batch_id, qty_remaining,
                                    value_remaining, unit_cost, received_at)
                            VALUES (:tenantId, :id, :variantId, :locationId, :state, :batchId, :qty, :value, :unitCost,
                                    :receivedAt)
                            ON CONFLICT (tenant_id, id) DO UPDATE
                               SET qty_remaining = EXCLUDED.qty_remaining, value_remaining = EXCLUDED.value_remaining
                            """)
                    .param("tenantId", tenant)
                    .param("id", added.id())
                    .param("variantId", key.variantId())
                    .param("locationId", key.locationId())
                    .param("state", key.stockState())
                    .param("batchId", key.batchId())
                    .param("qty", added.qtyRemaining())
                    .param("value", added.valueRemaining())
                    .param("unitCost", added.unitCost())
                    .param("receivedAt", Jsonb.utc(added.receivedAt()))
                    .update();
        }
    }
}
