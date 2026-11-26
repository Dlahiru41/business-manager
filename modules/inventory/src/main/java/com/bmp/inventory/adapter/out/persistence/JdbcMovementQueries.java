package com.bmp.inventory.adapter.out.persistence;

import com.bmp.inventory.domain.model.BalanceKey;
import com.bmp.inventory.domain.port.MovementQueries;
import com.bmp.inventory.domain.service.SaleReversal;
import com.bmp.shared.context.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link MovementQueries} on {@code inventory.stock_movement}. */
@Repository
class JdbcMovementQueries implements MovementQueries {

    private final JdbcClient jdbc;

    JdbcMovementQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean hasOpening(UUID variantId, UUID locationId, String stockState) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM inventory.stock_movement
                                        WHERE tenant_id = :tenantId AND variant_id = :variantId
                                          AND location_id = :locationId AND stock_state = :state AND type = 'OPENING')
                        """)
                .param("tenantId", TenantContext.require().value())
                .param("variantId", variantId)
                .param("locationId", locationId)
                .param("state", stockState)
                .query(Boolean.class)
                .single();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<SaleReversal.Booked> billMovements(UUID billId) {
        return jdbc.sql("""
                        SELECT m.source_line_id, m.variant_id, m.location_id, m.stock_state, m.batch_id, m.qty_base,
                               m.cost_amount
                          FROM inventory.stock_movement m
                         WHERE m.tenant_id = :tenantId AND m.type IN ('SALE', 'SALE_RETURN', 'SALE_VOID')
                           AND m.source_line_id IN (SELECT s.source_line_id FROM inventory.stock_movement s
                                                     WHERE s.tenant_id = :tenantId AND s.source_type = 'SALE'
                                                       AND s.source_id = :billId)
                         ORDER BY m.recorded_at, m.id
                        """)
                .param("tenantId", TenantContext.require().value())
                .param("billId", billId)
                .query((rs, row) -> new SaleReversal.Booked(
                        rs.getObject("source_line_id", UUID.class),
                        new BalanceKey(
                                rs.getObject("variant_id", UUID.class),
                                rs.getObject("location_id", UUID.class),
                                rs.getString("stock_state"),
                                rs.getObject("batch_id", UUID.class)),
                        rs.getBigDecimal("qty_base"),
                        rs.getBigDecimal("cost_amount")))
                .list();
    }
}
