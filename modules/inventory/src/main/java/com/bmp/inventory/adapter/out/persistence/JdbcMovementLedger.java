package com.bmp.inventory.adapter.out.persistence;

import com.bmp.inventory.domain.model.StockFlag;
import com.bmp.inventory.domain.port.MovementLedger;
import com.bmp.inventory.domain.service.PlannedMovement;
import com.bmp.persistence.jdbc.Jsonb;
import com.bmp.shared.context.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link MovementLedger} on {@code inventory.stock_movement}: inserts only (06 §6). */
@Repository
class JdbcMovementLedger implements MovementLedger {

    private final JdbcClient jdbc;

    JdbcMovementLedger(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(
            List<PlannedMovement> movements,
            String currency,
            Instant recordedAt,
            @Nullable UUID actorId,
            @Nullable UUID deviceId) {
        UUID tenant = TenantContext.require().value();
        for (PlannedMovement m : movements) {
            jdbc.sql("""
                            INSERT INTO inventory.stock_movement
                                   (id, tenant_id, variant_id, location_id, stock_state, batch_id, qty_base, unit_cost,
                                    cost_amount, currency, type, reason_code, source_type, source_id, source_line_id,
                                    flags, occurred_at, recorded_at, actor_id, device_id)
                            VALUES (:id, :tenantId, :variantId, :locationId, :state, :batchId, :qty, :unitCost, :cost,
                                    :currency, :type, :reason, :sourceType, :sourceId, :sourceLineId, :flags,
                                    :occurredAt, :recordedAt, :actorId, :deviceId)
                            """)
                    .param("id", m.id())
                    .param("tenantId", tenant)
                    .param("variantId", m.key().variantId())
                    .param("locationId", m.key().locationId())
                    .param("state", m.key().stockState())
                    .param("batchId", m.key().batchId())
                    .param("qty", m.qtyBase())
                    .param("unitCost", m.unitCost())
                    .param("cost", m.costAmount())
                    .param("currency", currency)
                    .param("type", m.type().name())
                    .param("reason", m.reasonCode())
                    .param("sourceType", m.sourceType())
                    .param("sourceId", m.sourceId())
                    .param("sourceLineId", m.sourceLineId())
                    .param(
                            "flags",
                            m.flags().stream().map(StockFlag::name).sorted().toArray(String[]::new))
                    .param("occurredAt", Jsonb.utc(m.occurredAt()))
                    .param("recordedAt", Jsonb.utc(recordedAt))
                    .param("actorId", actorId)
                    .param("deviceId", deviceId)
                    .update();
        }
    }
}
