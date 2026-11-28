package com.bmp.finance.adapter.out.persistence;

import com.bmp.finance.domain.model.Dimensions;
import com.bmp.finance.domain.model.JournalEntry;
import com.bmp.finance.domain.model.JournalLine;
import com.bmp.finance.domain.model.PartyRef;
import com.bmp.finance.domain.model.PartyType;
import com.bmp.finance.domain.model.Period;
import com.bmp.finance.domain.model.PostedJournal;
import com.bmp.finance.domain.model.SourceType;
import com.bmp.finance.domain.port.JournalLedger;
import com.bmp.persistence.jdbc.Jsonb;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link JournalLedger} on {@code finance.journal_entry} and {@code journal_line}: INSERT and SELECT only (FIN-04); the
 * lines go in one batch. SQL is built from fixed fragments; values are bound.
 */
@Repository
class JdbcJournalLedger implements JournalLedger {

    private static final String INSERT_LINE = """
            INSERT INTO finance.journal_line (id, tenant_id, journal_id, line_no, account_id, debit, credit, party_type,
                                              party_id, variant_id, product_id, channel, location_id, bill_id)
            VALUES (:id, :tenantId, :journalId, :lineNo, :accountId, :debit, :credit, :partyType, :partyId, :variantId,
                    :productId, :channel, :locationId, :billId)
            """;

    private static final String SELECT_JOURNAL = """
            SELECT j.id, j.number, j.entry_date, j.period, j.source_type, j.source_id, j.reverses_id
              FROM finance.journal_entry j
             WHERE j.tenant_id = :tenantId
            """;

    private static final String FIRST_OF_SOURCE = """
               AND j.source_type = :sourceType AND j.source_id = :sourceId
             ORDER BY j.posted_at, j.id
             LIMIT 1
            """;

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate batch;

    JdbcJournalLedger(JdbcClient jdbc, NamedParameterJdbcTemplate batch) {
        this.jdbc = jdbc;
        this.batch = batch;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(NewJournal journal) {
        UUID tenant = Tenants.current();
        JournalEntry entry = journal.entry();
        jdbc.sql("""
                        INSERT INTO finance.journal_entry (id, tenant_id, number, entry_date, period, source_type, source_id,
                                                           source_event_id, memo, reverses_id, currency, posted_at,
                                                           posted_by)
                        VALUES (:id, :tenantId, :number, :entryDate, :period, :sourceType, :sourceId, :sourceEventId,
                                :memo, :reversesId, :currency, :postedAt, :postedBy)
                        """)
                .param("id", journal.id())
                .param("tenantId", tenant)
                .param("number", journal.number())
                .param("entryDate", Date.valueOf(entry.entryDate()))
                .param("period", journal.period().toString())
                .param("sourceType", entry.sourceType().name())
                .param("sourceId", entry.sourceId())
                .param("sourceEventId", entry.sourceEventId())
                .param("memo", entry.memo())
                .param("reversesId", entry.reversesId())
                .param("currency", journal.currency())
                .param("postedAt", Jsonb.utc(journal.postedAt()))
                .param("postedBy", journal.postedBy())
                .update();
        List<SqlParameterSource> lines = new ArrayList<>();
        for (int i = 0; i < entry.lines().size(); i++) {
            JournalLine line = entry.lines().get(i);
            PartyRef party = line.party();
            Dimensions dims = line.dimensions();
            lines.add(new MapSqlParameterSource()
                    .addValue(
                            "id",
                            UUID.nameUUIDFromBytes(
                                    (journal.id() + ":" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .addValue("tenantId", tenant)
                    .addValue("journalId", journal.id())
                    .addValue("lineNo", i + 1)
                    .addValue("accountId", journal.accountIds().get(i))
                    .addValue("debit", line.debit())
                    .addValue("credit", line.credit())
                    .addValue("partyType", party == null ? null : party.type().name())
                    .addValue("partyId", party == null ? null : party.id())
                    .addValue("variantId", dims.variantId())
                    .addValue("productId", dims.productId())
                    .addValue("channel", dims.channel())
                    .addValue("locationId", dims.locationId())
                    .addValue("billId", dims.billId()));
        }
        batch.batchUpdate(INSERT_LINE, lines.toArray(SqlParameterSource[]::new));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PostedJournal> find(UUID journalId) {
        return jdbc.sql(SELECT_JOURNAL + " AND j.id = :id")
                .param("tenantId", Tenants.current())
                .param("id", journalId)
                .query(JdbcJournalLedger::header)
                .optional()
                .map(this::withLines);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PostedJournal> findFirst(SourceType type, UUID sourceId) {
        return jdbc.sql(SELECT_JOURNAL + FIRST_OF_SOURCE)
                .param("tenantId", Tenants.current())
                .param("sourceType", type.name())
                .param("sourceId", sourceId)
                .query(JdbcJournalLedger::header)
                .optional()
                .map(this::withLines);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean isReversed(UUID journalId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM finance.journal_entry
                                        WHERE tenant_id = :tenantId AND reverses_id = :id)
                        """)
                .param("tenantId", Tenants.current())
                .param("id", journalId)
                .query(Boolean.class)
                .single());
    }

    private PostedJournal withLines(PostedJournal header) {
        List<PostedJournal.Line> lines = jdbc.sql("""
                        SELECT account_id, debit, credit, party_type, party_id, variant_id, product_id, channel,
                               location_id, bill_id
                          FROM finance.journal_line
                         WHERE tenant_id = :tenantId AND journal_id = :journalId
                         ORDER BY line_no
                        """)
                .param("tenantId", Tenants.current())
                .param("journalId", header.id())
                .query((rs, row) -> new PostedJournal.Line(
                        rs.getObject("account_id", UUID.class),
                        rs.getBigDecimal("debit"),
                        rs.getBigDecimal("credit"),
                        party(rs),
                        new Dimensions(
                                rs.getObject("variant_id", UUID.class),
                                rs.getObject("product_id", UUID.class),
                                rs.getString("channel"),
                                rs.getObject("location_id", UUID.class),
                                rs.getObject("bill_id", UUID.class))))
                .list();
        return new PostedJournal(
                header.id(),
                header.number(),
                header.entryDate(),
                header.period(),
                header.sourceType(),
                header.sourceId(),
                header.reversesId(),
                lines);
    }

    private static PostedJournal header(ResultSet rs, int row) throws SQLException {
        return new PostedJournal(
                rs.getObject("id", UUID.class),
                rs.getString("number"),
                Objects.requireNonNull(rs.getDate("entry_date")).toLocalDate(),
                Period.parse(rs.getString("period")),
                SourceType.valueOf(rs.getString("source_type")),
                rs.getObject("source_id", UUID.class),
                rs.getObject("reverses_id", UUID.class),
                List.of());
    }

    static @org.jspecify.annotations.Nullable PartyRef party(ResultSet rs) throws SQLException {
        String type = rs.getString("party_type");
        return type == null ? null : new PartyRef(PartyType.valueOf(type), rs.getObject("party_id", UUID.class));
    }
}
