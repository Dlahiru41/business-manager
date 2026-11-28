package com.bmp.finance.adapter.out.persistence;

import com.bmp.finance.domain.model.FiscalPeriod;
import com.bmp.finance.domain.model.Period;
import com.bmp.finance.domain.model.PeriodStatus;
import com.bmp.finance.domain.port.PeriodStore;
import com.bmp.persistence.jdbc.Jsonb;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link PeriodStore} on {@code finance.fiscal_period}; rows are created when first needed (ADR-032 §8). */
@Repository
class JdbcPeriodStore implements PeriodStore {

    private static final String SELECT = """
            SELECT period, status, closed_at, closed_by FROM finance.fiscal_period
             WHERE tenant_id = :tenantId AND period = :period
            """;

    private final JdbcClient jdbc;

    JdbcPeriodStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public FiscalPeriod lockForPosting(Period period) {
        return lock(period, " FOR SHARE");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public FiscalPeriod lockForClose(Period period) {
        return lock(period, " FOR UPDATE");
    }

    private FiscalPeriod lock(Period period, String mode) {
        Optional<FiscalPeriod> row = select(period, mode);
        if (row.isPresent()) {
            return row.get();
        }
        jdbc.sql("""
                        INSERT INTO finance.fiscal_period (tenant_id, period, status) VALUES (:tenantId, :period, 'OPEN')
                        ON CONFLICT (tenant_id, period) DO NOTHING
                        """)
                .param("tenantId", Tenants.current())
                .param("period", period.toString())
                .update();
        return select(period, mode).orElseThrow();
    }

    private Optional<FiscalPeriod> select(Period period, String mode) {
        return jdbc.sql(SELECT + mode)
                .param("tenantId", Tenants.current())
                .param("period", period.toString())
                .query(JdbcPeriodStore::row)
                .optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<FiscalPeriod> find(Period period) {
        return select(period, "");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void save(FiscalPeriod period) {
        jdbc.sql("""
                        INSERT INTO finance.fiscal_period (tenant_id, period, status, closed_at, closed_by)
                        VALUES (:tenantId, :period, :status, :closedAt, :closedBy)
                        ON CONFLICT (tenant_id, period) DO UPDATE
                           SET status = EXCLUDED.status, closed_at = EXCLUDED.closed_at, closed_by = EXCLUDED.closed_by
                        """)
                .param("tenantId", Tenants.current())
                .param("period", period.period().toString())
                .param("status", period.status().name())
                .param("closedAt", period.closedAt() == null ? null : Jsonb.utc(period.closedAt()))
                .param("closedBy", period.closedBy())
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<FiscalPeriod> page(@Nullable Period before, int limit) {
        String filter = before == null ? "" : " AND k.period < :before";
        var statement = jdbc.sql("""
                        WITH known AS (
                            SELECT period FROM finance.fiscal_period WHERE tenant_id = :tenantId
                            UNION
                            SELECT DISTINCT period FROM finance.journal_entry WHERE tenant_id = :tenantId)
                        SELECT k.period, coalesce(f.status, 'OPEN') AS status, f.closed_at, f.closed_by
                          FROM known k
                          LEFT JOIN finance.fiscal_period f ON f.tenant_id = :tenantId AND f.period = k.period
                         WHERE 1 = 1%s
                         ORDER BY k.period DESC
                         LIMIT :limit
                        """.formatted(filter))
                .param("tenantId", Tenants.current())
                .param("limit", limit);
        if (before != null) {
            statement = statement.param("before", before.toString());
        }
        return statement.query(JdbcPeriodStore::row).list();
    }

    private static FiscalPeriod row(ResultSet rs, int row) throws SQLException {
        Timestamp closedAt = rs.getTimestamp("closed_at");
        return new FiscalPeriod(
                Period.parse(rs.getString("period")),
                PeriodStatus.valueOf(rs.getString("status")),
                closedAt == null ? null : Jsonb.instant(closedAt),
                rs.getObject("closed_by", UUID.class));
    }
}
