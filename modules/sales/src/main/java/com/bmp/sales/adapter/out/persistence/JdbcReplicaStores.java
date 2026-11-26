package com.bmp.sales.adapter.out.persistence;

import com.bmp.persistence.jdbc.Jsonb;
import com.bmp.sales.domain.port.CustomerStore;
import com.bmp.sales.domain.port.SaleItemStore;
import com.bmp.shared.context.TenantContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The catalog and party replicas; an upsert never replaces a newer aggregate version. */
final class JdbcReplicaStores {

    private JdbcReplicaStores() {}

    @Repository
    static class SaleItems implements SaleItemStore {

        private static final TypeReference<List<Unit>> UNITS = new TypeReference<>() {};

        private final JdbcClient jdbc;
        private final JsonMapper json;
        private final Clock clock;

        SaleItems(JdbcClient jdbc, JsonMapper json, Clock clock) {
            this.jdbc = jdbc;
            this.json = json;
            this.clock = clock;
        }

        @Override
        @Transactional(propagation = Propagation.MANDATORY)
        public Optional<SaleItem> find(UUID variantId) {
            return jdbc.sql("""
                            SELECT variant_id, product_id, sku, name, base_unit, weighable, packaging_units::text AS units,
                                   status
                              FROM sales.sale_item WHERE tenant_id = :tenantId AND variant_id = :variantId
                            """)
                    .param("tenantId", TenantContext.require().value())
                    .param("variantId", variantId)
                    .query((rs, row) -> new SaleItem(
                            rs.getObject("variant_id", UUID.class),
                            rs.getObject("product_id", UUID.class),
                            rs.getString("sku"),
                            rs.getString("name"),
                            rs.getString("base_unit"),
                            rs.getBoolean("weighable"),
                            json.readValue(rs.getString("units"), UNITS),
                            "ACTIVE".equals(rs.getString("status"))))
                    .optional();
        }

        @Override
        @Transactional(propagation = Propagation.MANDATORY)
        public void upsert(SaleItem item, long version) {
            jdbc.sql("""
                            INSERT INTO sales.sale_item (tenant_id, variant_id, product_id, sku, name, base_unit,
                                   weighable, packaging_units, status, aggregate_version, updated_at)
                            VALUES (:tenantId, :variantId, :productId, :sku, :name, :baseUnit, :weighable,
                                    CAST(:units AS jsonb), :status, :version, :now)
                            ON CONFLICT (tenant_id, variant_id) DO UPDATE
                               SET product_id = EXCLUDED.product_id, sku = EXCLUDED.sku, name = EXCLUDED.name,
                                   base_unit = EXCLUDED.base_unit, weighable = EXCLUDED.weighable,
                                   packaging_units = EXCLUDED.packaging_units, status = EXCLUDED.status,
                                   aggregate_version = EXCLUDED.aggregate_version, updated_at = EXCLUDED.updated_at
                             WHERE sales.sale_item.aggregate_version <= EXCLUDED.aggregate_version
                            """)
                    .param("tenantId", TenantContext.require().value())
                    .param("variantId", item.variantId())
                    .param("productId", item.productId())
                    .param("sku", item.sku())
                    .param("name", item.name())
                    .param("baseUnit", item.baseUnit())
                    .param("weighable", item.weighable())
                    .param("units", json.writeValueAsString(item.units()))
                    .param("status", item.active() ? "ACTIVE" : "INACTIVE")
                    .param("version", version)
                    .param("now", Jsonb.utc(clock.instant()))
                    .update();
        }

        @Override
        @Transactional(propagation = Propagation.MANDATORY)
        public void deactivate(UUID variantId, long version) {
            jdbc.sql("""
                            UPDATE sales.sale_item SET status = 'INACTIVE', aggregate_version = :version,
                                   updated_at = :now
                             WHERE tenant_id = :tenantId AND variant_id = :variantId AND aggregate_version <= :version
                            """)
                    .param("tenantId", TenantContext.require().value())
                    .param("variantId", variantId)
                    .param("version", version)
                    .param("now", Jsonb.utc(clock.instant()))
                    .update();
        }
    }

    @Repository
    static class Customers implements CustomerStore {

        private final JdbcClient jdbc;
        private final Clock clock;

        Customers(JdbcClient jdbc, Clock clock) {
            this.jdbc = jdbc;
            this.clock = clock;
        }

        @Override
        @Transactional(propagation = Propagation.MANDATORY)
        public Optional<Customer> find(UUID partyId) {
            return jdbc.sql("""
                            SELECT party_id, type, display_name, customer_group_id, status, credit_allowed, credit_limit
                              FROM sales.customer_ref WHERE tenant_id = :tenantId AND party_id = :partyId
                            """)
                    .param("tenantId", TenantContext.require().value())
                    .param("partyId", partyId)
                    .query((rs, row) -> new Customer(
                            rs.getObject("party_id", UUID.class),
                            rs.getString("type"),
                            rs.getString("display_name"),
                            rs.getObject("customer_group_id", UUID.class),
                            "ACTIVE".equals(rs.getString("status")),
                            rs.getBoolean("credit_allowed"),
                            rs.getBigDecimal("credit_limit")))
                    .optional();
        }

        @Override
        @Transactional(propagation = Propagation.MANDATORY)
        public void upsert(Customer customer, long version) {
            jdbc.sql("""
                            INSERT INTO sales.customer_ref (tenant_id, party_id, type, display_name, customer_group_id,
                                   status, credit_allowed, credit_limit, aggregate_version, updated_at)
                            VALUES (:tenantId, :partyId, :type, :name, :groupId, :status, :creditAllowed, :creditLimit,
                                    :version, :now)
                            ON CONFLICT (tenant_id, party_id) DO UPDATE
                               SET type = EXCLUDED.type, display_name = EXCLUDED.display_name,
                                   customer_group_id = EXCLUDED.customer_group_id, status = EXCLUDED.status,
                                   credit_allowed = EXCLUDED.credit_allowed, credit_limit = EXCLUDED.credit_limit,
                                   aggregate_version = EXCLUDED.aggregate_version, updated_at = EXCLUDED.updated_at
                             WHERE sales.customer_ref.aggregate_version <= EXCLUDED.aggregate_version
                            """)
                    .param("tenantId", TenantContext.require().value())
                    .param("partyId", customer.partyId())
                    .param("type", customer.type())
                    .param("name", customer.displayName())
                    .param("groupId", customer.customerGroupId())
                    .param("status", customer.active() ? "ACTIVE" : "INACTIVE")
                    .param("creditAllowed", customer.creditAllowed())
                    .param("creditLimit", customer.creditLimit() == null ? BigDecimal.ZERO : customer.creditLimit())
                    .param("version", version)
                    .param("now", Jsonb.utc(clock.instant()))
                    .update();
        }
    }
}
