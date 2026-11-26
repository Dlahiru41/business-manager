package com.bmp.payments.adapter.out.persistence;

import com.bmp.payments.domain.model.Payment;
import com.bmp.payments.domain.model.PaymentKind;
import com.bmp.payments.domain.model.PaymentMethod;
import com.bmp.payments.domain.model.Refund;
import com.bmp.payments.domain.port.PaymentLedger;
import com.bmp.persistence.jdbc.Jsonb;
import com.bmp.shared.context.ActorContext;
import com.bmp.shared.context.TenantContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link PaymentLedger} on {@code payments.payment} and {@code payments.refund}: INSERT and SELECT only. SQL is built
 * only from fixed fragments; values are bound.
 */
@Repository
class JdbcPaymentLedger implements PaymentLedger {

    private static final String CAPTURED = """
            SELECT p.id, p.kind, p.bill_id, p.tender_id, p.customer_id, p.method, p.amount, p.currency, p.tendered,
                   p.change_given, p.reference, p.register_id, p.drawer_session_id, p.device_id, p.business_date,
                   p.captured_at,
                   coalesce((SELECT sum(r.amount) FROM payments.refund r
                              WHERE r.tenant_id = p.tenant_id AND r.payment_id = p.id), 0) AS refunded
              FROM payments.payment p
             WHERE p.tenant_id = :tenantId
            """;

    private final JdbcClient jdbc;
    private final Clock clock;

    JdbcPaymentLedger(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(Payment p) {
        jdbc.sql("""
                        INSERT INTO payments.payment (id, tenant_id, kind, bill_id, tender_id, customer_id, method, amount,
                                                      currency, tendered, change_given, reference, register_id,
                                                      drawer_session_id, device_id, business_date, captured_at,
                                                      recorded_at, created_by)
                        VALUES (:id, :tenantId, :kind, :billId, :tenderId, :customerId, :method, :amount, :currency,
                                :tendered, :change, :reference, :registerId, :session, :deviceId, :businessDate,
                                :capturedAt, :recordedAt, :createdBy)
                        """)
                .param("id", p.id())
                .param("tenantId", tenant())
                .param("kind", p.kind().name())
                .param("billId", p.billId())
                .param("tenderId", p.tenderId())
                .param("customerId", p.customerId())
                .param("method", p.method().name())
                .param("amount", p.amount())
                .param("currency", p.currency())
                .param("tendered", p.tendered())
                .param("change", p.changeGiven())
                .param("reference", p.reference())
                .param("registerId", p.registerId())
                .param("session", p.drawerSessionId())
                .param("deviceId", p.deviceId())
                .param("businessDate", Date.valueOf(p.businessDate()))
                .param("capturedAt", Jsonb.utc(p.capturedAt()))
                .param("recordedAt", Jsonb.utc(clock.instant()))
                .param("createdBy", user())
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(Refund r) {
        jdbc.sql("""
                        INSERT INTO payments.refund (id, tenant_id, payment_id, amount, method, currency, reason,
                                                     credit_note_id, source_event_id, register_id, drawer_session_id,
                                                     business_date, created_at, created_by)
                        VALUES (:id, :tenantId, :paymentId, :amount, :method, :currency, :reason, :creditNoteId,
                                :sourceEventId, :registerId, :session, :businessDate, :createdAt, :createdBy)
                        """)
                .param("id", r.id())
                .param("tenantId", tenant())
                .param("paymentId", r.paymentId())
                .param("amount", r.amount())
                .param("method", r.method().name())
                .param("currency", r.currency())
                .param("reason", r.reason())
                .param("creditNoteId", r.creditNoteId())
                .param("sourceEventId", r.sourceEventId())
                .param("registerId", r.registerId())
                .param("session", r.drawerSessionId())
                .param("businessDate", Date.valueOf(r.businessDate()))
                .param("createdAt", Jsonb.utc(r.createdAt()))
                .param("createdBy", user())
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(List<UUID> paymentIds) {
        // Ledger rows are never updated, so they are not row-locked: a transaction-scoped advisory lock per payment
        // serialises refunds of it, always taken in id order.
        paymentIds.stream()
                .distinct()
                .sorted()
                .forEach(id -> jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                        .param("key", "payments.payment:" + tenant() + ":" + id)
                        .query()
                        .listOfRows());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Captured> find(UUID paymentId) {
        return jdbc.sql(CAPTURED + " AND p.id = :id")
                .param("tenantId", tenant())
                .param("id", paymentId)
                .query(JdbcPaymentLedger::captured)
                .optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Payment> findByTender(UUID tenderId) {
        return jdbc.sql(CAPTURED + " AND p.tender_id = :tenderId")
                .param("tenantId", tenant())
                .param("tenderId", tenderId)
                .query(JdbcPaymentLedger::captured)
                .optional()
                .map(Captured::payment);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Captured> ofBill(UUID billId) {
        return jdbc.sql(CAPTURED + " AND p.bill_id = :billId ORDER BY p.captured_at, p.id")
                .param("tenantId", tenant())
                .param("billId", billId)
                .query(JdbcPaymentLedger::captured)
                .list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean refundExists(UUID refundId) {
        return Boolean.TRUE.equals(
                jdbc.sql("SELECT EXISTS (SELECT 1 FROM payments.refund WHERE tenant_id = :tenantId AND id = :id)")
                        .param("tenantId", tenant())
                        .param("id", refundId)
                        .query(Boolean.class)
                        .single());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Refund> refundsOf(UUID paymentId) {
        return jdbc.sql("""
                        SELECT id, payment_id, amount, method, currency, reason, credit_note_id, source_event_id,
                               register_id, drawer_session_id, business_date, created_at
                          FROM payments.refund
                         WHERE tenant_id = :tenantId AND payment_id = :paymentId
                         ORDER BY created_at, id
                        """)
                .param("tenantId", tenant())
                .param("paymentId", paymentId)
                .query((rs, row) -> new Refund(
                        rs.getObject("id", UUID.class),
                        rs.getObject("payment_id", UUID.class),
                        rs.getBigDecimal("amount"),
                        PaymentMethod.valueOf(rs.getString("method")),
                        rs.getString("currency"),
                        rs.getString("reason"),
                        rs.getObject("credit_note_id", UUID.class),
                        rs.getObject("source_event_id", UUID.class),
                        rs.getObject("register_id", UUID.class),
                        rs.getObject("drawer_session_id", UUID.class),
                        Objects.requireNonNull(rs.getDate("business_date")).toLocalDate(),
                        Jsonb.instant(Objects.requireNonNull(rs.getTimestamp("created_at")))))
                .list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Captured> page(Filter filter, @Nullable UUID beforeId, int limit) {
        StringBuilder sql = new StringBuilder(CAPTURED);
        if (filter.billId() != null) {
            sql.append(" AND p.bill_id = :billId");
        }
        if (filter.customerId() != null) {
            sql.append(" AND p.customer_id = :customerId");
        }
        if (filter.method() != null) {
            sql.append(" AND p.method = :method");
        }
        if (filter.businessDate() != null) {
            sql.append(" AND p.business_date = :businessDate");
        }
        if (beforeId != null) {
            sql.append(" AND p.id < :beforeId");
        }
        var statement = jdbc.sql(sql.append(" ORDER BY p.id DESC LIMIT :limit").toString())
                .param("tenantId", tenant())
                .param("limit", limit);
        if (filter.billId() != null) {
            statement = statement.param("billId", filter.billId());
        }
        if (filter.customerId() != null) {
            statement = statement.param("customerId", filter.customerId());
        }
        if (filter.method() != null) {
            statement = statement.param(
                    "method", Objects.requireNonNull(filter.method()).name());
        }
        if (filter.businessDate() != null) {
            statement = statement.param("businessDate", Date.valueOf(Objects.requireNonNull(filter.businessDate())));
        }
        if (beforeId != null) {
            statement = statement.param("beforeId", beforeId);
        }
        return statement.query(JdbcPaymentLedger::captured).list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<MethodTotal> totals(LocalDate businessDate, @Nullable UUID registerId) {
        String register = registerId == null ? "" : " AND register_id = :registerId";
        var statement = jdbc.sql("""
                        WITH captured AS (
                            SELECT method, count(*) AS n, sum(amount) AS amount FROM payments.payment
                             WHERE tenant_id = :tenantId AND business_date = :businessDate%1$s GROUP BY method),
                        refunded AS (
                            SELECT method, sum(amount) AS amount FROM payments.refund
                             WHERE tenant_id = :tenantId AND business_date = :businessDate%1$s GROUP BY method)
                        SELECT coalesce(c.method, r.method) AS method, coalesce(c.n, 0) AS n,
                               coalesce(c.amount, 0) AS captured, coalesce(r.amount, 0) AS refunded
                          FROM captured c FULL OUTER JOIN refunded r ON r.method = c.method
                         ORDER BY 1
                        """.formatted(register))
                .param("tenantId", tenant())
                .param("businessDate", Date.valueOf(businessDate));
        if (registerId != null) {
            statement = statement.param("registerId", registerId);
        }
        return statement
                .query((rs, row) -> new MethodTotal(
                        PaymentMethod.valueOf(rs.getString("method")),
                        rs.getLong("n"),
                        rs.getBigDecimal("captured"),
                        rs.getBigDecimal("refunded")))
                .list();
    }

    private static Captured captured(ResultSet rs, int row) throws SQLException {
        BigDecimal refunded = rs.getBigDecimal("refunded");
        return new Captured(
                new Payment(
                        rs.getObject("id", UUID.class),
                        PaymentKind.valueOf(rs.getString("kind")),
                        rs.getObject("bill_id", UUID.class),
                        rs.getObject("tender_id", UUID.class),
                        rs.getObject("customer_id", UUID.class),
                        PaymentMethod.valueOf(rs.getString("method")),
                        rs.getBigDecimal("amount"),
                        rs.getString("currency"),
                        rs.getBigDecimal("tendered"),
                        rs.getBigDecimal("change_given"),
                        rs.getString("reference"),
                        rs.getObject("register_id", UUID.class),
                        rs.getObject("drawer_session_id", UUID.class),
                        rs.getObject("device_id", UUID.class),
                        Objects.requireNonNull(rs.getDate("business_date")).toLocalDate(),
                        Jsonb.instant(Objects.requireNonNull(rs.getTimestamp("captured_at")))),
                refunded == null ? BigDecimal.ZERO : refunded);
    }

    private static UUID tenant() {
        return TenantContext.require().value();
    }

    private static @Nullable UUID user() {
        var user = ActorContext.currentOrSystem().userId();
        return user == null ? null : user.value();
    }
}
