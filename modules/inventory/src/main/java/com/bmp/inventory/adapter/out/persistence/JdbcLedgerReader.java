package com.bmp.inventory.adapter.out.persistence;

import com.bmp.inventory.domain.model.BalanceKey;
import com.bmp.inventory.domain.model.MovementType;
import com.bmp.inventory.domain.port.LedgerReader;
import com.bmp.inventory.domain.service.BalanceReplay;
import com.bmp.shared.context.TenantContext;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Reads the tenant's whole ledger for a rebuild (INV-16); pilot volumes fit in memory. */
@Repository
class JdbcLedgerReader implements LedgerReader {

    private final JdbcClient jdbc;

    JdbcLedgerReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<BalanceKey, List<BalanceReplay.Recorded>> movementsByBalance() {
        Map<BalanceKey, List<BalanceReplay.Recorded>> byKey = new TreeMap<>();
        jdbc.sql("""
                        SELECT id, variant_id, location_id, stock_state, batch_id, type, qty_base, cost_amount,
                               occurred_at
                          FROM inventory.stock_movement
                         WHERE tenant_id = :tenantId
                         ORDER BY recorded_at, id
                        """).param("tenantId", TenantContext.require().value()).query(rs -> {
            BalanceKey key = new BalanceKey(
                    rs.getObject("variant_id", UUID.class),
                    rs.getObject("location_id", UUID.class),
                    rs.getString("stock_state"),
                    rs.getObject("batch_id", UUID.class));
            byKey.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new BalanceReplay.Recorded(
                            rs.getObject("id", UUID.class),
                            MovementType.valueOf(rs.getString("type")),
                            rs.getBigDecimal("qty_base"),
                            rs.getBigDecimal("cost_amount"),
                            rs.getObject("occurred_at", OffsetDateTime.class).toInstant()));
        });
        return byKey;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<BalanceKey> balanceKeys() {
        return jdbc.sql("""
                        SELECT variant_id, location_id, stock_state, batch_id FROM inventory.stock_balance
                         WHERE tenant_id = :tenantId
                        """)
                .param("tenantId", TenantContext.require().value())
                .query((rs, row) -> new BalanceKey(
                        rs.getObject("variant_id", UUID.class),
                        rs.getObject("location_id", UUID.class),
                        rs.getString("stock_state"),
                        rs.getObject("batch_id", UUID.class)))
                .list();
    }
}
