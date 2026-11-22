package com.bmp.pricing.adapter.out.persistence;

import com.bmp.persistence.jdbc.Jsonb;
import com.bmp.pricing.domain.model.VariantRef;
import com.bmp.pricing.domain.port.VariantRefStore;
import com.bmp.shared.context.TenantContext;
import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link VariantRefStore} on {@code pricing.variant_price_ref}: the upsert keeps the snapshot with the highest
 * aggregate version, so events that arrive out of order never move the replica backwards.
 */
@Repository
class JdbcVariantRefStore implements VariantRefStore {

    private static final TypeReference<List<VariantRef.Unit>> UNITS = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    JdbcVariantRefStore(JdbcClient jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void upsert(VariantRef ref) {
        jdbc.sql("""
                        INSERT INTO pricing.variant_price_ref
                               (variant_id, tenant_id, product_id, tax_category_id, status, aggregate_version,
                                packaging_units, updated_at)
                        VALUES (:variantId, :tenantId, :productId, :taxCategoryId, :status, :version, :units, :now)
                        ON CONFLICT (variant_id) DO UPDATE
                           SET product_id = EXCLUDED.product_id, tax_category_id = EXCLUDED.tax_category_id,
                               status = EXCLUDED.status, aggregate_version = EXCLUDED.aggregate_version,
                               packaging_units = EXCLUDED.packaging_units, updated_at = EXCLUDED.updated_at
                         WHERE pricing.variant_price_ref.aggregate_version < EXCLUDED.aggregate_version
                        """)
                .param("variantId", ref.variantId())
                .param("tenantId", TenantContext.require().value())
                .param("productId", ref.productId())
                .param("taxCategoryId", ref.taxCategoryId())
                .param("status", ref.active() ? "ACTIVE" : "INACTIVE")
                .param("version", ref.aggregateVersion())
                .param("units", Jsonb.of(json.writeValueAsString(ref.units())))
                .param("now", Jsonb.utc(clock.instant()))
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void deactivate(UUID variantId, UUID productId, long aggregateVersion) {
        jdbc.sql("""
                        INSERT INTO pricing.variant_price_ref
                               (variant_id, tenant_id, product_id, status, aggregate_version, packaging_units, updated_at)
                        VALUES (:variantId, :tenantId, :productId, 'INACTIVE', :version, '[]', :now)
                        ON CONFLICT (variant_id) DO UPDATE
                           SET status = 'INACTIVE', aggregate_version = EXCLUDED.aggregate_version,
                               updated_at = EXCLUDED.updated_at
                         WHERE pricing.variant_price_ref.aggregate_version < EXCLUDED.aggregate_version
                        """)
                .param("variantId", variantId)
                .param("tenantId", TenantContext.require().value())
                .param("productId", productId)
                .param("version", aggregateVersion)
                .param("now", Jsonb.utc(clock.instant()))
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, VariantRef> findAll(Collection<UUID> variantIds) {
        Map<UUID, VariantRef> refs = new HashMap<>();
        if (variantIds.isEmpty()) {
            return refs;
        }
        jdbc.sql("""
                        SELECT variant_id, product_id, tax_category_id, status, aggregate_version,
                               packaging_units::text AS units
                          FROM pricing.variant_price_ref
                         WHERE tenant_id = :tenantId AND variant_id IN (:ids)
                        """)
                .param("tenantId", TenantContext.require().value())
                .param("ids", List.copyOf(variantIds))
                .query((rs, row) -> new VariantRef(
                        rs.getObject("variant_id", UUID.class),
                        rs.getObject("product_id", UUID.class),
                        rs.getObject("tax_category_id", UUID.class),
                        "ACTIVE".equals(rs.getString("status")),
                        rs.getLong("aggregate_version"),
                        json.readValue(rs.getString("units"), UNITS)))
                .list()
                .forEach(ref -> refs.put(ref.variantId(), ref));
        return refs;
    }
}
