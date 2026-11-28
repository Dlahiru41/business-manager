package com.bmp.finance.adapter.out.persistence;

import com.bmp.finance.domain.model.AccountType;
import com.bmp.finance.domain.model.Dimensions;
import com.bmp.finance.domain.model.Period;
import com.bmp.finance.domain.model.SourceType;
import com.bmp.finance.domain.model.SystemKey;
import com.bmp.finance.domain.port.LedgerReader;
import com.bmp.persistence.jdbc.Jsonb;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link LedgerReader} with SQL over the journals and accounts of the current tenant. */
@Repository
class JdbcJournalReader implements LedgerReader {

    private static final String JOURNAL = """
            SELECT j.id, j.number, j.entry_date, j.period, j.source_type, j.source_id, j.source_event_id, j.memo,
                   j.reverses_id, (SELECT r.id FROM finance.journal_entry r
                                     WHERE r.tenant_id = j.tenant_id AND r.reverses_id = j.id) AS reversed_by,
                   j.currency, j.posted_at, j.posted_by
              FROM finance.journal_entry j
             WHERE j.tenant_id = :tenantId
            """;

    private static final String TOTALS = """
            SELECT a.id, a.code, a.name, a.type, a.system_key, sum(l.debit) AS debit, sum(l.credit) AS credit
              FROM finance.journal_line l
              JOIN finance.journal_entry j ON j.tenant_id = l.tenant_id AND j.id = l.journal_id
              JOIN finance.account a ON a.tenant_id = l.tenant_id AND a.id = l.account_id
             WHERE l.tenant_id = :tenantId AND %s
             GROUP BY a.id, a.code, a.name, a.type, a.system_key
             ORDER BY a.code
            """;

    private static final String CLEARING = """
            WITH clearing AS (
                SELECT l.bill_id, sum(l.debit - l.credit) AS amount, min(j.entry_date) AS since
                  FROM finance.journal_line l
                  JOIN finance.journal_entry j ON j.tenant_id = l.tenant_id AND j.id = l.journal_id
                  JOIN finance.account a ON a.tenant_id = l.tenant_id AND a.id = l.account_id
                 WHERE l.tenant_id = :tenantId AND a.system_key = 'POS_CLEARING' AND l.bill_id IS NOT NULL
                   AND j.entry_date <= :upTo
                 GROUP BY l.bill_id)
            SELECT bill_id, amount, since FROM clearing WHERE amount <> 0 AND %s ORDER BY since, bill_id
            """;

    private final JdbcClient jdbc;

    JdbcJournalReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<JournalView> page(Filter f, @Nullable Cursor after, int limit) {
        StringBuilder sql = new StringBuilder(JOURNAL);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenantId", Tenants.current());
        if (f.period() != null) {
            sql.append(" AND j.period = :period");
            params.put("period", Objects.requireNonNull(f.period()).toString());
        }
        if (f.sourceType() != null) {
            sql.append(" AND j.source_type = :sourceType");
            params.put("sourceType", Objects.requireNonNull(f.sourceType()).name());
        }
        if (f.sourceId() != null) {
            sql.append(" AND j.source_id = :sourceId");
            params.put("sourceId", f.sourceId());
        }
        if (f.accountId() != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM finance.journal_line x WHERE x.tenant_id = j.tenant_id"
                    + " AND x.journal_id = j.id AND x.account_id = :accountId)");
            params.put("accountId", f.accountId());
        }
        if (f.from() != null) {
            sql.append(" AND j.entry_date >= :from");
            params.put("from", Date.valueOf(Objects.requireNonNull(f.from())));
        }
        if (f.to() != null) {
            sql.append(" AND j.entry_date <= :to");
            params.put("to", Date.valueOf(Objects.requireNonNull(f.to())));
        }
        if (after != null) {
            sql.append(" AND (j.entry_date, j.id) < (:afterDate, :afterId)");
            params.put("afterDate", Date.valueOf(after.entryDate()));
            params.put("afterId", after.id());
        }
        sql.append(" ORDER BY j.entry_date DESC, j.id DESC LIMIT :limit");
        params.put("limit", limit);
        List<JournalView> headers = jdbc.sql(sql.toString())
                .params(params)
                .query(JdbcJournalReader::header)
                .list();
        return headers.stream().map(this::withLines).toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<JournalView> view(UUID journalId) {
        return jdbc.sql(JOURNAL + " AND j.id = :id")
                .param("tenantId", Tenants.current())
                .param("id", journalId)
                .query(JdbcJournalReader::header)
                .optional()
                .map(this::withLines);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<AccountTotal> totalsUpTo(LocalDate asOf) {
        return jdbc.sql(TOTALS.formatted("j.entry_date <= :asOf"))
                .param("tenantId", Tenants.current())
                .param("asOf", Date.valueOf(asOf))
                .query(JdbcJournalReader::total)
                .list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<AccountTotal> totalsOf(Period from, Period to, boolean withoutYearEndClose) {
        String where = "j.period >= :from AND j.period <= :to"
                + (withoutYearEndClose ? " AND j.source_type <> 'YEAR_END_CLOSE'" : "");
        return jdbc.sql(TOTALS.formatted(where))
                .param("tenantId", Tenants.current())
                .param("from", from.toString())
                .param("to", to.toString())
                .query(JdbcJournalReader::total)
                .list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ClearingDifference> posClearingDifferences(Period period) {
        return jdbc.sql(CLEARING.formatted("""
                        bill_id IN (SELECT l.bill_id FROM finance.journal_line l
                                      JOIN finance.journal_entry j ON j.tenant_id = l.tenant_id AND j.id = l.journal_id
                                     WHERE l.tenant_id = :tenantId AND j.period = :period AND l.bill_id IS NOT NULL)
                        """))
                .param("tenantId", Tenants.current())
                .param("upTo", Date.valueOf(period.lastDay()))
                .param("period", period.toString())
                .query(JdbcJournalReader::difference)
                .list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ClearingDifference> posClearingDifferencesBefore(LocalDate before) {
        return jdbc.sql(CLEARING.formatted("since < :before"))
                .param("tenantId", Tenants.current())
                .param("upTo", Date.valueOf(LocalDate.of(9999, 12, 31)))
                .param("before", Date.valueOf(before))
                .query(JdbcJournalReader::difference)
                .list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean hasJournals(Period period) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM finance.journal_entry WHERE tenant_id = :tenantId AND period = :p)
                        """)
                .param("tenantId", Tenants.current())
                .param("p", period.toString())
                .query(Boolean.class)
                .single());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Instant> lastPostedAt() {
        return jdbc.sql("SELECT max(posted_at) AS at FROM finance.journal_entry WHERE tenant_id = :tenantId")
                .param("tenantId", Tenants.current())
                .query((rs, row) -> {
                    Timestamp at = rs.getTimestamp("at");
                    return at == null ? null : Jsonb.instant(at);
                })
                .optional();
    }

    private JournalView withLines(JournalView header) {
        List<LineView> lines = jdbc.sql("""
                        SELECT l.id, l.account_id, a.code, a.system_key, l.debit, l.credit, l.party_type, l.party_id,
                               l.variant_id, l.product_id, l.channel, l.location_id, l.bill_id
                          FROM finance.journal_line l
                          JOIN finance.account a ON a.tenant_id = l.tenant_id AND a.id = l.account_id
                         WHERE l.tenant_id = :tenantId AND l.journal_id = :journalId
                         ORDER BY l.line_no
                        """)
                .param("tenantId", Tenants.current())
                .param("journalId", header.id())
                .query((rs, row) -> new LineView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("account_id", UUID.class),
                        rs.getString("code"),
                        key(rs.getString("system_key")),
                        rs.getBigDecimal("debit"),
                        rs.getBigDecimal("credit"),
                        JdbcJournalLedger.party(rs),
                        new Dimensions(
                                rs.getObject("variant_id", UUID.class),
                                rs.getObject("product_id", UUID.class),
                                rs.getString("channel"),
                                rs.getObject("location_id", UUID.class),
                                rs.getObject("bill_id", UUID.class))))
                .list();
        return new JournalView(
                header.id(),
                header.number(),
                header.entryDate(),
                header.period(),
                header.sourceType(),
                header.sourceId(),
                header.sourceEventId(),
                header.memo(),
                header.reversesId(),
                header.reversedById(),
                header.currency(),
                header.postedAt(),
                header.postedBy(),
                new ArrayList<>(lines));
    }

    private static JournalView header(ResultSet rs, int row) throws SQLException {
        return new JournalView(
                rs.getObject("id", UUID.class),
                rs.getString("number"),
                Objects.requireNonNull(rs.getDate("entry_date")).toLocalDate(),
                Period.parse(rs.getString("period")),
                SourceType.valueOf(rs.getString("source_type")),
                rs.getObject("source_id", UUID.class),
                rs.getObject("source_event_id", UUID.class),
                rs.getString("memo"),
                rs.getObject("reverses_id", UUID.class),
                rs.getObject("reversed_by", UUID.class),
                rs.getString("currency"),
                Jsonb.instant(Objects.requireNonNull(rs.getTimestamp("posted_at"))),
                rs.getObject("posted_by", UUID.class),
                List.of());
    }

    private static AccountTotal total(ResultSet rs, int row) throws SQLException {
        return new AccountTotal(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                AccountType.valueOf(rs.getString("type")),
                key(rs.getString("system_key")),
                rs.getBigDecimal("debit"),
                rs.getBigDecimal("credit"));
    }

    private static ClearingDifference difference(ResultSet rs, int row) throws SQLException {
        return new ClearingDifference(
                rs.getObject("bill_id", UUID.class),
                rs.getBigDecimal("amount"),
                Objects.requireNonNull(rs.getDate("since")).toLocalDate());
    }

    private static @Nullable SystemKey key(@Nullable String key) {
        return key == null ? null : SystemKey.valueOf(key);
    }
}
