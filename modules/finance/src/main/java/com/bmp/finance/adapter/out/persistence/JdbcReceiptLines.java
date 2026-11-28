package com.bmp.finance.adapter.out.persistence;

import com.bmp.finance.domain.port.ReceiptLines;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link ReceiptLines} on {@code finance.receipt_line}, insert only. */
@Repository
class JdbcReceiptLines implements ReceiptLines {

    private final JdbcClient jdbc;

    JdbcReceiptLines(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void save(UUID receiptId, Map<UUID, BigDecimal> totals) {
        totals.forEach((line, total) -> jdbc.sql("""
                        INSERT INTO finance.receipt_line (tenant_id, line_id, receipt_id, line_total)
                        VALUES (:tenantId, :lineId, :receiptId, :total)
                        ON CONFLICT (tenant_id, line_id) DO NOTHING
                        """)
                .param("tenantId", Tenants.current())
                .param("lineId", line)
                .param("receiptId", receiptId)
                .param("total", total)
                .update());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, BigDecimal> totals(Collection<UUID> lineIds) {
        Map<UUID, BigDecimal> totals = new HashMap<>();
        if (lineIds.isEmpty()) {
            return totals;
        }
        jdbc.sql("""
                        SELECT line_id, line_total FROM finance.receipt_line
                         WHERE tenant_id = :tenantId AND line_id IN (:lineIds)
                        """)
                .param("tenantId", Tenants.current())
                .param("lineIds", List.copyOf(lineIds))
                .query((rs, row) -> Map.entry(rs.getObject("line_id", UUID.class), rs.getBigDecimal("line_total")))
                .list()
                .forEach(e -> totals.put(e.getKey(), e.getValue()));
        return totals;
    }
}
