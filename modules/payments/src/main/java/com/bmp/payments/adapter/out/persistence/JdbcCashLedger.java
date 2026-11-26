package com.bmp.payments.adapter.out.persistence;

import com.bmp.payments.domain.model.CashMovement;
import com.bmp.payments.domain.model.CashMovementType;
import com.bmp.payments.domain.port.CashLedger;
import com.bmp.persistence.jdbc.Jsonb;
import com.bmp.shared.context.ActorContext;
import com.bmp.shared.context.TenantContext;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link CashLedger} on {@code payments.cash_movement}: INSERT and SELECT only. */
@Repository
class JdbcCashLedger implements CashLedger {

    private final JdbcClient jdbc;
    private final Clock clock;

    JdbcCashLedger(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(CashMovement m) {
        var user = ActorContext.currentOrSystem().userId();
        jdbc.sql("""
                        INSERT INTO payments.cash_movement (id, tenant_id, drawer_session_id, type, amount, reason,
                                                            source_type, source_id, occurred_at, recorded_at, created_by)
                        VALUES (:id, :tenantId, :session, :type, :amount, :reason, :sourceType, :sourceId, :occurredAt,
                                :recordedAt, :createdBy)
                        """)
                .param("id", m.id())
                .param("tenantId", TenantContext.require().value())
                .param("session", m.drawerSessionId())
                .param("type", m.type().name())
                .param("amount", m.amount())
                .param("reason", m.reason())
                .param("sourceType", m.sourceType())
                .param("sourceId", m.sourceId())
                .param("occurredAt", Jsonb.utc(m.occurredAt()))
                .param("recordedAt", Jsonb.utc(clock.instant()))
                .param("createdBy", user == null ? null : user.value())
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean exists(UUID movementId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM payments.cash_movement WHERE tenant_id = :tenantId AND id = :id)
                        """)
                .param("tenantId", TenantContext.require().value())
                .param("id", movementId)
                .query(Boolean.class)
                .single());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<CashMovement> ofSession(UUID drawerSessionId) {
        return jdbc.sql("""
                        SELECT id, drawer_session_id, type, amount, reason, source_type, source_id, occurred_at
                          FROM payments.cash_movement
                         WHERE tenant_id = :tenantId AND drawer_session_id = :session
                         ORDER BY occurred_at, recorded_at, id
                        """)
                .param("tenantId", TenantContext.require().value())
                .param("session", drawerSessionId)
                .query((rs, row) -> new CashMovement(
                        rs.getObject("id", UUID.class),
                        rs.getObject("drawer_session_id", UUID.class),
                        CashMovementType.valueOf(rs.getString("type")),
                        rs.getBigDecimal("amount"),
                        rs.getString("reason"),
                        rs.getString("source_type"),
                        rs.getObject("source_id", UUID.class),
                        Jsonb.instant(Objects.requireNonNull(rs.getTimestamp("occurred_at")))))
                .list();
    }
}
