package com.bmp.finance.adapter.out.persistence;

import com.bmp.finance.domain.model.CashDrop;
import com.bmp.finance.domain.model.Expense;
import com.bmp.finance.domain.model.PaidFrom;
import com.bmp.finance.domain.model.SupplierPayment;
import com.bmp.finance.domain.port.DocumentLedger;
import com.bmp.persistence.jdbc.Jsonb;
import com.bmp.shared.context.ActorContext;
import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link DocumentLedger} on {@code finance.expense}, {@code supplier_payment(_allocation)} and {@code cash_drop}. */
@Repository
class JdbcDocumentLedger implements DocumentLedger {

    private static final String EXPENSE = """
            SELECT id, category_id, expense_date, amount, currency, paid_from, drawer_session_id, supplier_id, due_date,
                   description, attachment_keys, journal_id, created_at
              FROM finance.expense WHERE tenant_id = :tenantId
            """;
    private static final String PAYMENT = """
            SELECT id, supplier_id, amount, currency, paid_from, drawer_session_id, reference, paid_at, journal_id
              FROM finance.supplier_payment WHERE tenant_id = :tenantId
            """;
    private static final String DROP = """
            SELECT id, drawer_session_id, register_id, amount, currency, reference, dropped_at, journal_id
              FROM finance.cash_drop WHERE tenant_id = :tenantId
            """;

    private final JdbcClient jdbc;

    JdbcDocumentLedger(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(Expense e) {
        jdbc.sql("""
                        INSERT INTO finance.expense (id, tenant_id, category_id, expense_date, amount, currency, paid_from,
                                                     drawer_session_id, supplier_id, due_date, description,
                                                     attachment_keys, journal_id, created_at, created_by)
                        VALUES (:id, :tenantId, :categoryId, :date, :amount, :currency, :paidFrom, :session, :supplier,
                                :due, :description, :keys, :journalId, :createdAt, :createdBy)
                        """)
                .param("id", e.id())
                .param("tenantId", Tenants.current())
                .param("categoryId", e.categoryId())
                .param("date", Date.valueOf(e.expenseDate()))
                .param("amount", e.amount())
                .param("currency", e.currency())
                .param("paidFrom", e.paidFrom().name())
                .param("session", e.drawerSessionId())
                .param("supplier", e.supplierId())
                .param("due", e.dueDate() == null ? null : Date.valueOf(e.dueDate()))
                .param("description", e.description())
                .param("keys", e.attachmentKeys().toArray(String[]::new))
                .param("journalId", e.journalId())
                .param("createdAt", Jsonb.utc(e.createdAt()))
                .param("createdBy", user())
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Expense> expense(UUID id) {
        return jdbc.sql(EXPENSE + " AND id = :id")
                .param("tenantId", Tenants.current())
                .param("id", id)
                .query(JdbcDocumentLedger::expense)
                .optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Expense> expenses(
            @Nullable LocalDate from,
            @Nullable LocalDate to,
            @Nullable UUID categoryId,
            @Nullable PaidFrom paidFrom,
            @Nullable Cursor after,
            int limit) {
        StringBuilder sql = new StringBuilder(EXPENSE);
        if (from != null) {
            sql.append(" AND expense_date >= :from");
        }
        if (to != null) {
            sql.append(" AND expense_date <= :to");
        }
        if (categoryId != null) {
            sql.append(" AND category_id = :categoryId");
        }
        if (paidFrom != null) {
            sql.append(" AND paid_from = :paidFrom");
        }
        if (after != null) {
            sql.append(" AND (expense_date, id) < (:afterDate, :afterId)");
        }
        var statement = jdbc.sql(sql.append(" ORDER BY expense_date DESC, id DESC LIMIT :limit")
                        .toString())
                .param("tenantId", Tenants.current())
                .param("limit", limit);
        if (from != null) {
            statement = statement.param("from", Date.valueOf(from));
        }
        if (to != null) {
            statement = statement.param("to", Date.valueOf(to));
        }
        if (categoryId != null) {
            statement = statement.param("categoryId", categoryId);
        }
        if (paidFrom != null) {
            statement = statement.param("paidFrom", paidFrom.name());
        }
        if (after != null) {
            statement = statement.param("afterDate", Date.valueOf(after.date())).param("afterId", after.id());
        }
        return statement.query(JdbcDocumentLedger::expense).list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(SupplierPayment p) {
        jdbc.sql("""
                        INSERT INTO finance.supplier_payment (id, tenant_id, supplier_id, amount, currency, paid_from,
                                                              drawer_session_id, reference, paid_at, journal_id,
                                                              created_at, created_by)
                        VALUES (:id, :tenantId, :supplier, :amount, :currency, :paidFrom, :session, :reference, :paidAt,
                                :journalId, :paidAt, :createdBy)
                        """)
                .param("id", p.id())
                .param("tenantId", Tenants.current())
                .param("supplier", p.supplierId())
                .param("amount", p.amount())
                .param("currency", p.currency())
                .param("paidFrom", p.fromDrawer() ? "CASH_DRAWER" : "BANK")
                .param("session", p.drawerSessionId())
                .param("reference", p.reference())
                .param("paidAt", Jsonb.utc(p.paidAt()))
                .param("journalId", p.journalId())
                .param("createdBy", user())
                .update();
        for (SupplierPayment.Allocated a : p.allocations()) {
            jdbc.sql("""
                            INSERT INTO finance.supplier_payment_allocation (tenant_id, payment_id, document_id, amount)
                            VALUES (:tenantId, :paymentId, :documentId, :amount)
                            """)
                    .param("tenantId", Tenants.current())
                    .param("paymentId", p.id())
                    .param("documentId", a.documentId())
                    .param("amount", a.amount())
                    .update();
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<SupplierPayment> supplierPayment(UUID id) {
        return jdbc.sql(PAYMENT + " AND id = :id")
                .param("tenantId", Tenants.current())
                .param("id", id)
                .query(this::payment)
                .optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<SupplierPayment> supplierPayments(@Nullable UUID supplierId, @Nullable UUID beforeId, int limit) {
        String sql = PAYMENT + (supplierId == null ? "" : " AND supplier_id = :supplier")
                + (beforeId == null ? "" : " AND id < :before") + " ORDER BY id DESC LIMIT :limit";
        var statement = jdbc.sql(sql).param("tenantId", Tenants.current()).param("limit", limit);
        if (supplierId != null) {
            statement = statement.param("supplier", supplierId);
        }
        if (beforeId != null) {
            statement = statement.param("before", beforeId);
        }
        return statement.query(this::payment).list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(CashDrop d) {
        jdbc.sql("""
                        INSERT INTO finance.cash_drop (id, tenant_id, drawer_session_id, register_id, amount, currency,
                                                       reference, dropped_at, journal_id, created_at, created_by)
                        VALUES (:id, :tenantId, :session, :register, :amount, :currency, :reference, :droppedAt,
                                :journalId, :droppedAt, :createdBy)
                        """)
                .param("id", d.id())
                .param("tenantId", Tenants.current())
                .param("session", d.drawerSessionId())
                .param("register", d.registerId())
                .param("amount", d.amount())
                .param("currency", d.currency())
                .param("reference", d.reference())
                .param("droppedAt", Jsonb.utc(d.droppedAt()))
                .param("journalId", d.journalId())
                .param("createdBy", user())
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<CashDrop> cashDrop(UUID id) {
        return jdbc.sql(DROP + " AND id = :id")
                .param("tenantId", Tenants.current())
                .param("id", id)
                .query(JdbcDocumentLedger::drop)
                .optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<CashDrop> cashDrops(@Nullable UUID drawerSessionId, @Nullable UUID beforeId, int limit) {
        String sql = DROP + (drawerSessionId == null ? "" : " AND drawer_session_id = :session")
                + (beforeId == null ? "" : " AND id < :before") + " ORDER BY id DESC LIMIT :limit";
        var statement = jdbc.sql(sql).param("tenantId", Tenants.current()).param("limit", limit);
        if (drawerSessionId != null) {
            statement = statement.param("session", drawerSessionId);
        }
        if (beforeId != null) {
            statement = statement.param("before", beforeId);
        }
        return statement.query(JdbcDocumentLedger::drop).list();
    }

    private SupplierPayment payment(ResultSet rs, int row) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        List<SupplierPayment.Allocated> allocations = jdbc.sql("""
                        SELECT document_id, amount FROM finance.supplier_payment_allocation
                         WHERE tenant_id = :tenantId AND payment_id = :id ORDER BY document_id
                        """)
                .param("tenantId", Tenants.current())
                .param("id", id)
                .query((r, n) -> new SupplierPayment.Allocated(
                        r.getObject("document_id", UUID.class), r.getBigDecimal("amount")))
                .list();
        return new SupplierPayment(
                id,
                rs.getObject("supplier_id", UUID.class),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getString("paid_from").equals("CASH_DRAWER"),
                rs.getObject("drawer_session_id", UUID.class),
                rs.getString("reference"),
                Jsonb.instant(Objects.requireNonNull(rs.getTimestamp("paid_at"))),
                allocations,
                rs.getObject("journal_id", UUID.class));
    }

    private static Expense expense(ResultSet rs, int row) throws SQLException {
        Date due = rs.getDate("due_date");
        Array keys = rs.getArray("attachment_keys");
        return new Expense(
                rs.getObject("id", UUID.class),
                rs.getObject("category_id", UUID.class),
                Objects.requireNonNull(rs.getDate("expense_date")).toLocalDate(),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                PaidFrom.valueOf(rs.getString("paid_from")),
                rs.getObject("drawer_session_id", UUID.class),
                rs.getObject("supplier_id", UUID.class),
                due == null ? null : due.toLocalDate(),
                rs.getString("description"),
                keys == null ? List.of() : Arrays.asList((String[]) keys.getArray()),
                rs.getObject("journal_id", UUID.class),
                Jsonb.instant(Objects.requireNonNull(rs.getTimestamp("created_at"))));
    }

    private static CashDrop drop(ResultSet rs, int row) throws SQLException {
        return new CashDrop(
                rs.getObject("id", UUID.class),
                rs.getObject("drawer_session_id", UUID.class),
                rs.getObject("register_id", UUID.class),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getString("reference"),
                Jsonb.instant(Objects.requireNonNull(rs.getTimestamp("dropped_at"))),
                rs.getObject("journal_id", UUID.class));
    }

    private static @Nullable UUID user() {
        var user = ActorContext.currentOrSystem().userId();
        return user == null ? null : user.value();
    }
}
