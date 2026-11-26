package com.bmp.sales.domain.model;

import com.bmp.sales.domain.error.SalesErrorCode;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.id.TenantId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A cashier's shift on a register (SAL-12): online bills need an OPEN one; offline bills attach to the shift that
 * covers their time, or to a synthetic offline shift.
 */
@Entity
@Table(schema = "sales", name = "shift")
public class Shift {

    @Id
    private UUID id;

    @org.hibernate.annotations.TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "register_id", nullable = false, updatable = false)
    private UUID registerId;

    @Column(name = "cashier_id", updatable = false)
    private @Nullable UUID cashierId;

    @Column(nullable = false)
    private String status;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "closed_at")
    private @Nullable Instant closedAt;

    @Column(nullable = false, updatable = false)
    private boolean offline;

    @Version
    private @Nullable Long version;

    @SuppressWarnings("NullAway.Init") // JPA
    protected Shift() {}

    public static Shift open(
            UUID id, TenantId tenant, UUID registerId, @Nullable UUID cashierId, Instant at, boolean offline) {
        Shift shift = new Shift();
        shift.id = Objects.requireNonNull(id, "id");
        shift.tenantId = tenant.value();
        shift.registerId = registerId;
        shift.cashierId = cashierId;
        shift.status = "OPEN";
        shift.openedAt = at;
        shift.offline = offline;
        return shift;
    }

    public void close(Instant at) {
        if (!isOpen()) {
            throw new BusinessRuleViolation(SalesErrorCode.SHIFT_STATE_INVALID, Map.of("status", status));
        }
        this.status = "CLOSED";
        this.closedAt = at.isBefore(openedAt) ? openedAt : at;
    }

    public boolean isOpen() {
        return "OPEN".equals(status);
    }

    /** Whether the shift was running at {@code at}. */
    public boolean covers(Instant at) {
        return !at.isBefore(openedAt) && (closedAt == null || !at.isAfter(closedAt));
    }

    public UUID id() {
        return id;
    }

    public UUID registerId() {
        return registerId;
    }

    public @Nullable UUID cashierId() {
        return cashierId;
    }

    public String status() {
        return status;
    }

    public Instant openedAt() {
        return openedAt;
    }

    public @Nullable Instant closedAt() {
        return closedAt;
    }

    public boolean offline() {
        return offline;
    }

    public long version() {
        return version != null ? version : 0;
    }
}
