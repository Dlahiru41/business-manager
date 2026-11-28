package com.bmp.finance.adapter.out.persistence;

import com.bmp.finance.domain.model.DocumentType;
import com.bmp.finance.domain.model.OpenItem;
import com.bmp.finance.domain.model.PartyRef;
import com.bmp.finance.domain.model.PartyType;
import com.bmp.finance.domain.model.Settlement;
import com.bmp.finance.domain.port.OpenItemStore;
import com.bmp.persistence.jdbc.Jsonb;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link OpenItemStore} on {@code finance.open_item}, {@code open_item_settlement} (insert only) and the
 * {@code party_balance} projection, whose row is the party's lock.
 */
@Repository
class JdbcOpenItemStore implements OpenItemStore {

    private static final String ITEM = """
            SELECT id, party_type, party_id, document_type, document_id, amount, settled, document_date, due_date
              FROM finance.open_item
             WHERE tenant_id = :tenantId AND party_type = :partyType AND party_id = :partyId
            """;

    private final JdbcClient jdbc;

    JdbcOpenItemStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockParty(PartyRef party) {
        jdbc.sql("""
                        INSERT INTO finance.party_balance (tenant_id, party_type, party_id, balance)
                        VALUES (:tenantId, :partyType, :partyId, 0)
                        ON CONFLICT (tenant_id, party_type, party_id) DO NOTHING
                        """)
                .param("tenantId", Tenants.current())
                .param("partyType", party.type().name())
                .param("partyId", party.id())
                .update();
        jdbc.sql("""
                        SELECT balance FROM finance.party_balance
                         WHERE tenant_id = :tenantId AND party_type = :partyType AND party_id = :partyId
                           FOR UPDATE
                        """)
                .param("tenantId", Tenants.current())
                .param("partyType", party.type().name())
                .param("partyId", party.id())
                .query(BigDecimal.class)
                .single();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<OpenItem> openItems(PartyRef party) {
        return jdbc.sql(ITEM + " AND status = 'OPEN' ORDER BY due_date, document_date, id")
                .param("tenantId", Tenants.current())
                .param("partyType", party.type().name())
                .param("partyId", party.id())
                .query(JdbcOpenItemStore::item)
                .list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<OpenItem> findByDocument(PartyRef party, DocumentType type, UUID documentId) {
        return jdbc.sql(ITEM + " AND document_type = :documentType AND document_id = :documentId")
                .param("tenantId", Tenants.current())
                .param("partyType", party.type().name())
                .param("partyId", party.id())
                .param("documentType", type.name())
                .param("documentId", documentId)
                .query(JdbcOpenItemStore::item)
                .optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(OpenItem item, Instant at) {
        jdbc.sql("""
                        INSERT INTO finance.open_item (id, tenant_id, party_type, party_id, document_type, document_id,
                                                       amount, settled, document_date, due_date, status, created_at,
                                                       updated_at)
                        VALUES (:id, :tenantId, :partyType, :partyId, :documentType, :documentId, :amount, :settled,
                                :documentDate, :dueDate, :status, :at, :at)
                        """)
                .param("id", item.id())
                .param("tenantId", Tenants.current())
                .param("partyType", item.party().type().name())
                .param("partyId", item.party().id())
                .param("documentType", item.documentType().name())
                .param("documentId", item.documentId())
                .param("amount", item.amount())
                .param("settled", item.settled())
                .param("documentDate", Date.valueOf(item.documentDate()))
                .param("dueDate", Date.valueOf(item.dueDate()))
                .param("status", status(item))
                .param("at", Jsonb.utc(at))
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(OpenItem item, Instant at) {
        jdbc.sql("""
                        UPDATE finance.open_item SET settled = :settled, status = :status, updated_at = :at
                         WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("settled", item.settled())
                .param("status", status(item))
                .param("at", Jsonb.utc(at))
                .param("tenantId", Tenants.current())
                .param("id", item.id())
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void settled(List<Settlement> settlements, Instant at, Supplier<UUID> ids) {
        for (Settlement s : settlements) {
            jdbc.sql("""
                            INSERT INTO finance.open_item_settlement (id, tenant_id, item_id, against_item_id, amount,
                                                                      settled_at)
                            VALUES (:id, :tenantId, :itemId, :againstId, :amount, :at)
                            """)
                    .param("id", ids.get())
                    .param("tenantId", Tenants.current())
                    .param("itemId", s.itemId())
                    .param("againstId", s.againstItemId())
                    .param("amount", s.amount())
                    .param("at", Jsonb.utc(at))
                    .update();
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void balance(PartyRef party, BigDecimal balance, @Nullable LocalDate oldestOpenDate) {
        jdbc.sql("""
                        UPDATE finance.party_balance SET balance = :balance, oldest_open_date = :oldest
                         WHERE tenant_id = :tenantId AND party_type = :partyType AND party_id = :partyId
                        """)
                .param("balance", balance)
                .param("oldest", oldestOpenDate == null ? null : Date.valueOf(oldestOpenDate))
                .param("tenantId", Tenants.current())
                .param("partyType", party.type().name())
                .param("partyId", party.id())
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Balance> recomputed() {
        return jdbc.sql("""
                        SELECT party_type, party_id, sum(amount - settled) AS balance,
                               min(document_date) FILTER (WHERE amount > 0 AND status = 'OPEN') AS oldest
                          FROM finance.open_item
                         WHERE tenant_id = :tenantId AND status = 'OPEN'
                         GROUP BY party_type, party_id
                        """)
                .param("tenantId", Tenants.current())
                .query(JdbcOpenItemStore::balance)
                .list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Balance> stored() {
        return jdbc.sql("""
                        SELECT party_type, party_id, balance, oldest_open_date AS oldest
                          FROM finance.party_balance WHERE tenant_id = :tenantId
                        """)
                .param("tenantId", Tenants.current())
                .query(JdbcOpenItemStore::balance)
                .list();
    }

    private static Balance balance(ResultSet rs, int row) throws SQLException {
        Date oldest = rs.getDate("oldest");
        return new Balance(
                new PartyRef(PartyType.valueOf(rs.getString("party_type")), rs.getObject("party_id", UUID.class)),
                rs.getBigDecimal("balance"),
                oldest == null ? null : oldest.toLocalDate());
    }

    private static String status(OpenItem item) {
        return item.isOpen() ? "OPEN" : "SETTLED";
    }

    static OpenItem item(ResultSet rs, int row) throws SQLException {
        return new OpenItem(
                rs.getObject("id", UUID.class),
                new PartyRef(PartyType.valueOf(rs.getString("party_type")), rs.getObject("party_id", UUID.class)),
                DocumentType.valueOf(rs.getString("document_type")),
                rs.getObject("document_id", UUID.class),
                rs.getBigDecimal("amount"),
                rs.getBigDecimal("settled"),
                Objects.requireNonNull(rs.getDate("document_date")).toLocalDate(),
                Objects.requireNonNull(rs.getDate("due_date")).toLocalDate());
    }
}
