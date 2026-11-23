package com.bmp.inventory.adapter.out.persistence;

import com.bmp.inventory.domain.port.MovementQueries;
import com.bmp.shared.context.TenantContext;
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
}
