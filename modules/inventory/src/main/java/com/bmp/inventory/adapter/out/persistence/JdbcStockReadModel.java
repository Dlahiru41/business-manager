package com.bmp.inventory.adapter.out.persistence;

import com.bmp.inventory.application.query.MovementView;
import com.bmp.inventory.application.query.StockReadModel;
import com.bmp.persistence.jdbc.Jsonb;
import com.bmp.shared.context.TenantContext;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Balances, movements, valuation and the reorder list of the current tenant, read with SQL. */
@Repository
class JdbcStockReadModel implements StockReadModel {

    private static final String AVAILABLE = """
            (SELECT coalesce(sum(x.on_hand - x.reserved), 0) FROM inventory.stock_balance x
              WHERE x.tenant_id = %1$s.tenant_id AND x.variant_id = %1$s.variant_id
                AND x.location_id = %1$s.location_id)""";

    private final JdbcClient jdbc;

    JdbcStockReadModel(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<BalanceRow> balances(BalanceFilter filter, @Nullable List<String> after, int limit) {
        String sql = """
                SELECT b.variant_id, i.product_id, i.sku, b.location_id, b.stock_state, b.batch_id,
                       bt.code AS batch_code, bt.expires_on, b.on_hand, b.reserved, b.avg_unit_cost, b.stock_value,
                       p.reorder_level, (p.reorder_level IS NOT NULL AND %s <= p.reorder_level) AS below_reorder,
                       b.last_movement_at
                  FROM inventory.stock_balance b
                  JOIN inventory.stock_item i ON i.tenant_id = b.tenant_id AND i.variant_id = b.variant_id
                  LEFT JOIN inventory.batch bt ON bt.tenant_id = b.tenant_id AND bt.id = b.batch_id
                  LEFT JOIN inventory.reorder_policy p
                         ON p.tenant_id = b.tenant_id AND p.variant_id = b.variant_id AND p.location_id = b.location_id
                 WHERE b.tenant_id = :tenantId
                   AND (CAST(:variantId AS uuid) IS NULL OR b.variant_id = :variantId)
                   AND (CAST(:locationId AS uuid) IS NULL OR b.location_id = :locationId)
                   AND (CAST(:state AS text) IS NULL OR b.stock_state = :state)
                   AND (NOT :negative OR b.on_hand < 0)
                   AND (NOT :below OR (p.reorder_level IS NOT NULL AND %s <= p.reorder_level))
                   AND (CAST(:afterVariant AS uuid) IS NULL
                        OR (b.variant_id, b.location_id, b.stock_state, b.batch_id)
                           > (CAST(:afterVariant AS uuid), CAST(:afterLocation AS uuid), CAST(:afterState AS text),
                              CAST(:afterBatch AS uuid)))
                 ORDER BY b.variant_id, b.location_id, b.stock_state, b.batch_id
                 LIMIT :limit
                """.formatted(AVAILABLE.formatted("b"), AVAILABLE.formatted("b"));
        return jdbc.sql(sql)
                .param("tenantId", TenantContext.require().value())
                .param("variantId", filter.variantId())
                .param("locationId", filter.locationId())
                .param("state", filter.stockState())
                .param("negative", filter.negativeOnly())
                .param("below", filter.belowReorderOnly())
                .param("afterVariant", after == null ? null : UUID.fromString(after.get(0)))
                .param("afterLocation", after == null ? null : UUID.fromString(after.get(1)))
                .param("afterState", after == null ? null : after.get(2))
                .param("afterBatch", after == null ? null : UUID.fromString(after.get(3)))
                .param("limit", limit)
                .query(JdbcStockReadModel::balance)
                .list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<MovementView> movements(
            MovementFilter filter, @Nullable Instant beforeAt, @Nullable UUID beforeId, int limit) {
        return jdbc.sql("""
                        SELECT id, variant_id, location_id, stock_state, batch_id, qty_base, unit_cost, cost_amount,
                               currency, type, reason_code, source_type, source_id, source_line_id, occurred_at,
                               recorded_at, actor_id, flags
                          FROM inventory.stock_movement
                         WHERE tenant_id = :tenantId
                           AND (CAST(:variantId AS uuid) IS NULL OR variant_id = :variantId)
                           AND (CAST(:locationId AS uuid) IS NULL OR location_id = :locationId)
                           AND (CAST(:type AS text) IS NULL OR type = :type)
                           AND (CAST(:from AS timestamptz) IS NULL OR occurred_at >= :from)
                           AND (CAST(:to AS timestamptz) IS NULL OR occurred_at < :to)
                           AND (CAST(:beforeAt AS timestamptz) IS NULL
                                OR (occurred_at, id) < (CAST(:beforeAt AS timestamptz), CAST(:beforeId AS uuid)))
                         ORDER BY occurred_at DESC, id DESC
                         LIMIT :limit
                        """)
                .param("tenantId", TenantContext.require().value())
                .param("variantId", filter.variantId())
                .param("locationId", filter.locationId())
                .param("type", filter.type())
                .param("from", filter.from() == null ? null : Jsonb.utc(filter.from()))
                .param("to", filter.to() == null ? null : Jsonb.utc(filter.to()))
                .param("beforeAt", beforeAt == null ? null : Jsonb.utc(beforeAt))
                .param("beforeId", beforeId)
                .param("limit", limit)
                .query(JdbcStockReadModel::movement)
                .list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ValuationRow> valuation(Instant asOf, Grouping groupBy, @Nullable UUID locationId) {
        List<String> keyAndLabel = switch (groupBy) {
            case VARIANT -> List.of("m.variant_id", "i.sku");
            case PRODUCT -> List.of("i.product_id", "sp.name");
            case CATEGORY -> List.of("coalesce(sp.category_id, CAST(:uncategorised AS uuid))", "CAST(NULL AS text)");
            case LOCATION -> List.of("m.location_id", "l.name");
        };
        String key = keyAndLabel.get(0);
        String label = keyAndLabel.get(1);
        return jdbc.sql("""
                        SELECT %s AS key, %s AS label, sum(m.qty_base) AS qty, sum(m.cost_amount) AS value
                          FROM inventory.stock_movement m
                          JOIN inventory.stock_item i ON i.tenant_id = m.tenant_id AND i.variant_id = m.variant_id
                          LEFT JOIN inventory.stock_product sp
                                 ON sp.tenant_id = m.tenant_id AND sp.product_id = i.product_id
                          LEFT JOIN inventory.location_ref l
                                 ON l.tenant_id = m.tenant_id AND l.location_id = m.location_id
                         WHERE m.tenant_id = :tenantId AND m.occurred_at <= :asOf
                           AND (CAST(:locationId AS uuid) IS NULL OR m.location_id = :locationId)
                         GROUP BY 1, 2
                        HAVING sum(m.qty_base) <> 0 OR sum(m.cost_amount) <> 0
                         ORDER BY 2 NULLS LAST, 1
                        """.formatted(key, label))
                .param("tenantId", TenantContext.require().value())
                .param("asOf", Jsonb.utc(asOf))
                .param("locationId", locationId)
                .param("uncategorised", UNCATEGORISED)
                .query((rs, row) -> new ValuationRow(
                        rs.getObject("key", UUID.class),
                        rs.getString("label"),
                        rs.getBigDecimal("qty"),
                        rs.getBigDecimal("value")))
                .list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ReorderRow> reorderList(@Nullable UUID locationId) {
        return jdbc.sql("""
                        SELECT p.variant_id, i.sku, p.location_id, t.available, p.reorder_level, p.reorder_qty,
                               p.preferred_supplier_id,
                               (SELECT m.unit_cost FROM inventory.stock_movement m
                                 WHERE m.tenant_id = p.tenant_id AND m.variant_id = p.variant_id
                                   AND m.type IN ('RECEIPT', 'OPENING') AND m.qty_base > 0
                                 ORDER BY m.occurred_at DESC, m.id DESC LIMIT 1) AS last_cost
                          FROM inventory.reorder_policy p
                          JOIN inventory.stock_item i ON i.tenant_id = p.tenant_id AND i.variant_id = p.variant_id
                          CROSS JOIN LATERAL (SELECT %s AS available) t
                         WHERE p.tenant_id = :tenantId
                           AND (CAST(:locationId AS uuid) IS NULL OR p.location_id = :locationId)
                           AND t.available <= p.reorder_level
                         ORDER BY i.sku, p.location_id
                        """.formatted(AVAILABLE.formatted("p")))
                .param("tenantId", TenantContext.require().value())
                .param("locationId", locationId)
                .query((rs, row) -> new ReorderRow(
                        rs.getObject("variant_id", UUID.class),
                        rs.getString("sku"),
                        rs.getObject("location_id", UUID.class),
                        rs.getBigDecimal("available"),
                        rs.getBigDecimal("reorder_level"),
                        rs.getBigDecimal("reorder_qty"),
                        rs.getObject("preferred_supplier_id", UUID.class),
                        rs.getBigDecimal("last_cost")))
                .list();
    }

    private static BalanceRow balance(ResultSet rs, int row) throws SQLException {
        return new BalanceRow(
                rs.getObject("variant_id", UUID.class),
                rs.getObject("product_id", UUID.class),
                rs.getString("sku"),
                rs.getObject("location_id", UUID.class),
                rs.getString("stock_state"),
                rs.getObject("batch_id", UUID.class),
                rs.getString("batch_code"),
                rs.getObject("expires_on", LocalDate.class),
                rs.getBigDecimal("on_hand"),
                rs.getBigDecimal("reserved"),
                rs.getBigDecimal("avg_unit_cost"),
                rs.getBigDecimal("stock_value"),
                rs.getBigDecimal("reorder_level"),
                rs.getBoolean("below_reorder"),
                instant(rs, "last_movement_at"));
    }

    private static MovementView movement(ResultSet rs, int row) throws SQLException {
        UUID batch = rs.getObject("batch_id", UUID.class);
        Array flags = rs.getArray("flags");
        return new MovementView(
                rs.getObject("id", UUID.class),
                rs.getObject("variant_id", UUID.class),
                rs.getObject("location_id", UUID.class),
                rs.getString("stock_state"),
                batch.equals(com.bmp.inventory.domain.model.BalanceKey.NO_BATCH) ? null : batch,
                rs.getBigDecimal("qty_base"),
                rs.getBigDecimal("unit_cost"),
                rs.getBigDecimal("cost_amount"),
                rs.getString("currency"),
                rs.getString("type"),
                rs.getString("reason_code"),
                rs.getString("source_type"),
                rs.getObject("source_id", UUID.class),
                rs.getObject("source_line_id", UUID.class),
                java.util.Objects.requireNonNull(instant(rs, "occurred_at")),
                java.util.Objects.requireNonNull(instant(rs, "recorded_at")),
                rs.getObject("actor_id", UUID.class),
                flags == null ? List.of() : Arrays.asList((String[]) flags.getArray()));
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
