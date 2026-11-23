package com.bmp.inventory.adapter.out.persistence;

import com.bmp.inventory.domain.model.StockItem;
import com.bmp.inventory.domain.model.StockUnit;
import com.bmp.inventory.domain.port.StockItemStore;
import com.bmp.persistence.jdbc.Jsonb;
import com.bmp.shared.context.TenantContext;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** {@link StockItemStore} on {@code inventory.stock_item} and {@code inventory.stock_product}. */
@Repository
class JdbcStockItemStore implements StockItemStore {

    private static final TypeReference<List<StockUnit>> UNITS = new TypeReference<>() {};
    private static final String COLUMNS = """
            variant_id, product_id, sku, name, base_unit, stock_states, sell_mode, packaging_units::text AS units,
            track_batches, track_expiry, decimal_qty, status, aggregate_version""";

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    JdbcStockItemStore(JdbcClient jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void upsert(StockItem item) {
        jdbc.sql("""
                        INSERT INTO inventory.stock_item
                               (tenant_id, variant_id, product_id, sku, name, base_unit, stock_states, sell_mode,
                                packaging_units, track_batches, track_expiry, decimal_qty, status, aggregate_version,
                                updated_at)
                        VALUES (:tenantId, :variantId, :productId, :sku, :name, :baseUnit, :states, :sellMode, :units,
                                :batches, :expiry, :decimalQty, :status, :version, :now)
                        ON CONFLICT (tenant_id, variant_id) DO UPDATE
                           SET product_id = EXCLUDED.product_id, sku = EXCLUDED.sku, name = EXCLUDED.name,
                               base_unit = EXCLUDED.base_unit, stock_states = EXCLUDED.stock_states,
                               sell_mode = EXCLUDED.sell_mode, packaging_units = EXCLUDED.packaging_units,
                               track_batches = EXCLUDED.track_batches, track_expiry = EXCLUDED.track_expiry,
                               decimal_qty = EXCLUDED.decimal_qty, status = EXCLUDED.status,
                               aggregate_version = EXCLUDED.aggregate_version, updated_at = EXCLUDED.updated_at
                         WHERE inventory.stock_item.aggregate_version < EXCLUDED.aggregate_version
                        """)
                .param("tenantId", TenantContext.require().value())
                .param("variantId", item.variantId())
                .param("productId", item.productId())
                .param("sku", item.sku())
                .param("name", item.name())
                .param("baseUnit", item.baseUom())
                .param("states", item.stockStates().toArray(String[]::new))
                .param("sellMode", item.sellMode())
                .param("units", Jsonb.of(json.writeValueAsString(item.units())))
                .param("batches", item.trackBatches())
                .param("expiry", item.trackExpiry())
                .param("decimalQty", item.decimalQty())
                .param("status", item.active() ? "ACTIVE" : "INACTIVE")
                .param("version", item.aggregateVersion())
                .param("now", Jsonb.utc(clock.instant()))
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void deactivate(UUID variantId, UUID productId, String sku, long aggregateVersion) {
        jdbc.sql("""
                        INSERT INTO inventory.stock_item
                               (tenant_id, variant_id, product_id, sku, name, base_unit, stock_states, sell_mode,
                                packaging_units, track_batches, track_expiry, decimal_qty, status, aggregate_version,
                                updated_at)
                        VALUES (:tenantId, :variantId, :productId, :sku, :sku, '', '{}', 'PREPACKED', '[]', false,
                                false, false, 'INACTIVE', :version, :now)
                        ON CONFLICT (tenant_id, variant_id) DO UPDATE
                           SET status = 'INACTIVE', aggregate_version = EXCLUDED.aggregate_version,
                               updated_at = EXCLUDED.updated_at
                         WHERE inventory.stock_item.aggregate_version < EXCLUDED.aggregate_version
                        """)
                .param("tenantId", TenantContext.require().value())
                .param("variantId", variantId)
                .param("productId", productId)
                .param("sku", sku)
                .param("version", aggregateVersion)
                .param("now", Jsonb.utc(clock.instant()))
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void upsertProduct(UUID productId, String name, @Nullable UUID categoryId, long aggregateVersion) {
        jdbc.sql("""
                        INSERT INTO inventory.stock_product (tenant_id, product_id, name, category_id, aggregate_version)
                        VALUES (:tenantId, :productId, :name, :categoryId, :version)
                        ON CONFLICT (tenant_id, product_id) DO UPDATE
                           SET name = EXCLUDED.name, category_id = EXCLUDED.category_id,
                               aggregate_version = EXCLUDED.aggregate_version
                         WHERE inventory.stock_product.aggregate_version < EXCLUDED.aggregate_version
                        """)
                .param("tenantId", TenantContext.require().value())
                .param("productId", productId)
                .param("name", name)
                .param("categoryId", categoryId)
                .param("version", aggregateVersion)
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<StockItem> find(UUID variantId) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM inventory.stock_item WHERE tenant_id = :tenantId AND variant_id = :id")
                .param("tenantId", TenantContext.require().value())
                .param("id", variantId)
                .query(this::item)
                .optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, StockItem> findAll(Collection<UUID> variantIds) {
        Map<UUID, StockItem> items = new HashMap<>();
        if (variantIds.isEmpty()) {
            return items;
        }
        jdbc.sql("SELECT " + COLUMNS
                        + " FROM inventory.stock_item WHERE tenant_id = :tenantId AND variant_id IN (:ids)")
                .param("tenantId", TenantContext.require().value())
                .param("ids", List.copyOf(variantIds))
                .query(this::item)
                .list()
                .forEach(item -> items.put(item.variantId(), item));
        return items;
    }

    private StockItem item(ResultSet rs, int row) throws SQLException {
        Array states = rs.getArray("stock_states");
        return new StockItem(
                rs.getObject("variant_id", UUID.class),
                rs.getObject("product_id", UUID.class),
                rs.getString("sku"),
                rs.getString("name"),
                rs.getString("base_unit"),
                List.of((String[]) states.getArray()),
                rs.getString("sell_mode"),
                json.readValue(rs.getString("units"), UNITS),
                rs.getBoolean("track_batches"),
                rs.getBoolean("track_expiry"),
                rs.getBoolean("decimal_qty"),
                rs.getString("status").equals("ACTIVE"),
                rs.getLong("aggregate_version"));
    }
}
