package com.bmp.inventory.adapter.out.persistence;

import com.bmp.inventory.domain.model.BalanceKey;
import com.bmp.inventory.domain.model.BalanceState;
import com.bmp.inventory.domain.port.BalanceStore;
import com.bmp.inventory.domain.service.PlannedMovement;
import com.bmp.persistence.jdbc.Jsonb;
import com.bmp.shared.context.TenantContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link BalanceStore} on {@code inventory.stock_balance}; one row lock per key, taken in key order. */
@Repository
class JdbcBalanceStore implements BalanceStore {

    private final JdbcClient jdbc;
    private final Clock clock;

    JdbcBalanceStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<BalanceKey, BalanceState> lock(Collection<BalanceKey> keys) {
        Map<BalanceKey, BalanceState> locked = new LinkedHashMap<>();
        UUID tenant = TenantContext.require().value();
        for (BalanceKey key : new TreeSet<>(keys)) {
            jdbc.sql("""
                            INSERT INTO inventory.stock_balance
                                   (tenant_id, variant_id, location_id, stock_state, batch_id, updated_at)
                            VALUES (:tenantId, :variantId, :locationId, :state, :batchId, :now)
                            ON CONFLICT DO NOTHING
                            """)
                    .param("tenantId", tenant)
                    .param("variantId", key.variantId())
                    .param("locationId", key.locationId())
                    .param("state", key.stockState())
                    .param("batchId", key.batchId())
                    .param("now", Jsonb.utc(clock.instant()))
                    .update();
            BalanceState state = jdbc.sql("""
                            SELECT on_hand, stock_value, avg_unit_cost FROM inventory.stock_balance
                             WHERE tenant_id = :tenantId AND variant_id = :variantId AND location_id = :locationId
                               AND stock_state = :state AND batch_id = :batchId
                               FOR UPDATE
                            """)
                    .param("tenantId", tenant)
                    .param("variantId", key.variantId())
                    .param("locationId", key.locationId())
                    .param("state", key.stockState())
                    .param("batchId", key.batchId())
                    .query((rs, row) -> new BalanceState(
                            rs.getBigDecimal("on_hand"),
                            rs.getBigDecimal("stock_value"),
                            rs.getBigDecimal("avg_unit_cost"),
                            List.of()))
                    .single();
            locked.put(key, state);
        }
        return locked;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<BalanceKey> batchKeys(UUID variantId, UUID locationId, String stockState) {
        return jdbc.sql("""
                        SELECT batch_id FROM inventory.stock_balance
                         WHERE tenant_id = :tenantId AND variant_id = :variantId AND location_id = :locationId
                           AND stock_state = :state AND batch_id <> :noBatch
                         ORDER BY batch_id
                        """)
                .param("tenantId", TenantContext.require().value())
                .param("variantId", variantId)
                .param("locationId", locationId)
                .param("state", stockState)
                .param("noBatch", BalanceKey.NO_BATCH)
                .query((rs, row) ->
                        new BalanceKey(variantId, locationId, stockState, rs.getObject("batch_id", UUID.class)))
                .list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(Map<BalanceKey, BalanceState> balances, List<PlannedMovement> movements) {
        Map<BalanceKey, PlannedMovement> last = new HashMap<>();
        movements.forEach(m -> last.put(m.key(), m));
        List<BalanceKey> keys = new ArrayList<>(last.keySet());
        keys.sort(null);
        UUID tenant = TenantContext.require().value();
        for (BalanceKey key : keys) {
            BalanceState state = balances.get(key);
            PlannedMovement movement = last.get(key);
            if (state == null || movement == null) {
                continue;
            }
            jdbc.sql("""
                            UPDATE inventory.stock_balance
                               SET on_hand = :onHand, stock_value = :value, avg_unit_cost = :avg,
                                   last_movement_id = :movementId, last_movement_at = :movedAt,
                                   version = version + 1, updated_at = :now
                             WHERE tenant_id = :tenantId AND variant_id = :variantId AND location_id = :locationId
                               AND stock_state = :state AND batch_id = :batchId
                            """)
                    .param("onHand", state.onHand())
                    .param("value", state.value())
                    .param("avg", state.avgUnitCost())
                    .param("movementId", movement.id())
                    .param("movedAt", Jsonb.utc(movement.occurredAt()))
                    .param("now", Jsonb.utc(clock.instant()))
                    .param("tenantId", tenant)
                    .param("variantId", key.variantId())
                    .param("locationId", key.locationId())
                    .param("state", key.stockState())
                    .param("batchId", key.batchId())
                    .update();
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void overwrite(Map<BalanceKey, BalanceState> rebuilt) {
        List<BalanceKey> keys = new ArrayList<>(rebuilt.keySet());
        keys.sort(null);
        UUID tenant = TenantContext.require().value();
        for (BalanceKey key : keys) {
            BalanceState state = rebuilt.get(key);
            if (state == null) {
                continue;
            }
            jdbc.sql("""
                            UPDATE inventory.stock_balance
                               SET on_hand = :onHand, stock_value = :value, avg_unit_cost = :avg,
                                   version = version + 1, updated_at = :now
                             WHERE tenant_id = :tenantId AND variant_id = :variantId AND location_id = :locationId
                               AND stock_state = :state AND batch_id = :batchId
                            """)
                    .param("onHand", state.onHand())
                    .param("value", state.value())
                    .param("avg", state.avgUnitCost())
                    .param("now", Jsonb.utc(clock.instant()))
                    .param("tenantId", tenant)
                    .param("variantId", key.variantId())
                    .param("locationId", key.locationId())
                    .param("state", key.stockState())
                    .param("batchId", key.batchId())
                    .update();
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal available(UUID variantId, UUID locationId) {
        return jdbc.sql("""
                        SELECT coalesce(sum(on_hand - reserved), 0) FROM inventory.stock_balance
                         WHERE tenant_id = :tenantId AND variant_id = :variantId AND location_id = :locationId
                        """)
                .param("tenantId", TenantContext.require().value())
                .param("variantId", variantId)
                .param("locationId", locationId)
                .query(BigDecimal.class)
                .single();
    }
}
