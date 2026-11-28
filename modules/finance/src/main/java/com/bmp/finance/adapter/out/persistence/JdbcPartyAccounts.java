package com.bmp.finance.adapter.out.persistence;

import com.bmp.finance.domain.model.OpenItem;
import com.bmp.finance.domain.model.PartyRef;
import com.bmp.finance.domain.model.PartyType;
import com.bmp.finance.domain.port.PartyAccounts;
import com.bmp.finance.domain.service.Aging;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link PartyAccounts} on the open items, the balance projection and the overdue notices. */
@Repository
class JdbcPartyAccounts implements PartyAccounts {

    private static final String ITEMS = """
            SELECT id, party_type, party_id, document_type, document_id, amount, settled, document_date, due_date
              FROM finance.open_item
             WHERE tenant_id = :tenantId AND party_type = :partyType
            """;

    private static final String BETWEEN = """
               AND party_id = :partyId AND document_date BETWEEN :from AND :to
             ORDER BY document_date, id
            """;

    private final JdbcClient jdbc;

    JdbcPartyAccounts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> partiesWithOpenItems(
            PartyType type, boolean overdueOnly, LocalDate asOf, @Nullable UUID after, int limit) {
        String sql = """
                SELECT DISTINCT party_id FROM finance.open_item
                 WHERE tenant_id = :tenantId AND party_type = :partyType AND status = 'OPEN'
                """ + (overdueOnly ? " AND amount > 0 AND due_date < :asOf" : "")
                + (after == null ? "" : " AND party_id > :after") + " ORDER BY party_id LIMIT :limit";
        var statement = jdbc.sql(sql)
                .param("tenantId", Tenants.current())
                .param("partyType", type.name())
                .param("limit", limit);
        if (overdueOnly) {
            statement = statement.param("asOf", Date.valueOf(asOf));
        }
        if (after != null) {
            statement = statement.param("after", after);
        }
        return statement.query((rs, row) -> rs.getObject(1, UUID.class)).list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<OpenItem> openItems(PartyType type, Collection<UUID> parties) {
        if (parties.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(ITEMS + " AND status = 'OPEN' AND party_id IN (:parties) ORDER BY party_id, due_date, id")
                .param("tenantId", Tenants.current())
                .param("partyType", type.name())
                .param("parties", List.copyOf(parties))
                .query(JdbcOpenItemStore::item)
                .list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Aging.Buckets totals(PartyType type, LocalDate asOf, boolean overdueOnly) {
        String parties = overdueOnly
                ? " AND party_id IN (SELECT party_id FROM finance.open_item WHERE tenant_id = :tenantId"
                        + " AND party_type = :partyType AND status = 'OPEN' AND amount > 0 AND due_date < :asOf)"
                : "";
        return jdbc.sql("""
                        SELECT coalesce(sum(r) FILTER (WHERE amount < 0 OR :asOf - due_date <= 30), 0) AS b0,
                               coalesce(sum(r) FILTER (WHERE amount > 0 AND :asOf - due_date BETWEEN 31 AND 60), 0) AS b1,
                               coalesce(sum(r) FILTER (WHERE amount > 0 AND :asOf - due_date BETWEEN 61 AND 90), 0) AS b2,
                               coalesce(sum(r) FILTER (WHERE amount > 0 AND :asOf - due_date > 90), 0) AS b3
                          FROM (SELECT amount, amount - settled AS r, due_date FROM finance.open_item
                                 WHERE tenant_id = :tenantId AND party_type = :partyType AND status = 'OPEN'%s) i
                        """.formatted(parties))
                .param("tenantId", Tenants.current())
                .param("partyType", type.name())
                .param("asOf", Date.valueOf(asOf))
                .query((rs, row) -> new Aging.Buckets(
                        rs.getBigDecimal("b0"), rs.getBigDecimal("b1"), rs.getBigDecimal("b2"), rs.getBigDecimal("b3")))
                .single();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal amountBefore(PartyRef party, LocalDate date) {
        return jdbc.sql("""
                        SELECT coalesce(sum(amount), 0) FROM finance.open_item
                         WHERE tenant_id = :tenantId AND party_type = :partyType AND party_id = :partyId
                           AND document_date < :date
                        """)
                .param("tenantId", Tenants.current())
                .param("partyType", party.type().name())
                .param("partyId", party.id())
                .param("date", Date.valueOf(date))
                .query(BigDecimal.class)
                .single();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<OpenItem> itemsBetween(PartyRef party, LocalDate from, LocalDate to) {
        return jdbc.sql(ITEMS + BETWEEN)
                .param("tenantId", Tenants.current())
                .param("partyType", party.type().name())
                .param("partyId", party.id())
                .param("from", Date.valueOf(from))
                .param("to", Date.valueOf(to))
                .query(JdbcOpenItemStore::item)
                .list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean isKnown(PartyRef party) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM finance.party_ref WHERE tenant_id = :tenantId AND party_id = :p)
                            OR EXISTS (SELECT 1 FROM finance.party_balance
                                        WHERE tenant_id = :tenantId AND party_type = :partyType AND party_id = :p)
                        """)
                .param("tenantId", Tenants.current())
                .param("partyType", party.type().name())
                .param("p", party.id())
                .query(Boolean.class)
                .single());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<BigDecimal> balance(PartyRef party) {
        return jdbc.sql("""
                        SELECT balance FROM finance.party_balance
                         WHERE tenant_id = :tenantId AND party_type = :partyType AND party_id = :partyId
                        """)
                .param("tenantId", Tenants.current())
                .param("partyType", party.type().name())
                .param("partyId", party.id())
                .query(BigDecimal.class)
                .optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Overdue> overdueCustomers(LocalDate dueBefore) {
        return jdbc.sql("""
                        SELECT party_id, sum(amount - settled) AS overdue, min(due_date) AS oldest
                          FROM finance.open_item
                         WHERE tenant_id = :tenantId AND party_type = 'CUSTOMER' AND status = 'OPEN' AND amount > 0
                           AND due_date < :dueBefore
                         GROUP BY party_id ORDER BY party_id
                        """)
                .param("tenantId", Tenants.current())
                .param("dueBefore", Date.valueOf(dueBefore))
                .query((rs, row) -> new Overdue(
                        rs.getObject("party_id", UUID.class),
                        rs.getBigDecimal("overdue"),
                        Objects.requireNonNull(rs.getDate("oldest")).toLocalDate()))
                .list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<LocalDate> lastNotified(UUID customerId) {
        return jdbc.sql("""
                        SELECT last_notified_on FROM finance.overdue_notice
                         WHERE tenant_id = :tenantId AND customer_id = :c
                        """)
                .param("tenantId", Tenants.current())
                .param("c", customerId)
                .query(LocalDate.class)
                .optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void notified(UUID customerId, LocalDate on) {
        jdbc.sql("""
                        INSERT INTO finance.overdue_notice (tenant_id, customer_id, last_notified_on)
                        VALUES (:tenantId, :c, :on)
                        ON CONFLICT (tenant_id, customer_id) DO UPDATE SET last_notified_on = EXCLUDED.last_notified_on
                        """)
                .param("tenantId", Tenants.current())
                .param("c", customerId)
                .param("on", Date.valueOf(on))
                .update();
    }
}
