package com.bmp.payments.domain.model;

import com.bmp.payments.domain.error.PaymentsErrorCode;
import com.bmp.shared.audit.Auditable;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A register's cash drawer from its opening float to its reconciliation (PAY-04). Cash moves while it is OPEN; a count
 * by denomination (repeatable until reconciled) gives the variance against the expected cash; reconciling closes it.
 * Sales and refunds keep landing on a COUNTED drawer until it is reconciled, so reconciling compares the count with the
 * expected cash at that moment.
 */
@Entity
@Table(schema = "payments", name = "drawer_session")
public class DrawerSession implements Auditable {

    @Id
    private UUID id;

    @org.hibernate.annotations.TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "register_id", nullable = false, updatable = false)
    private UUID registerId;

    @Column(name = "opened_by", updatable = false)
    private @Nullable UUID openedBy;

    @Column(name = "opening_float", nullable = false, updatable = false)
    private BigDecimal openingFloat;

    @Column(name = "counted_cash")
    private @Nullable BigDecimal countedCash;

    @Column(name = "expected_cash")
    private @Nullable BigDecimal expectedCash;

    private @Nullable BigDecimal variance;

    @ElementCollection(fetch = FetchType.EAGER) // a dozen rows at most, read with every drawer
    @CollectionTable(
            schema = "payments",
            name = "drawer_denomination",
            joinColumns = @JoinColumn(name = "drawer_session_id"))
    @OrderBy("value DESC")
    private List<DenominationCount> denominations = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DrawerStatus status = DrawerStatus.OPEN;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "counted_at")
    private @Nullable Instant countedAt;

    @Column(name = "counted_by")
    private @Nullable UUID countedBy;

    @Column(name = "reconciled_at")
    private @Nullable Instant reconciledAt;

    @Column(name = "reconciled_by")
    private @Nullable UUID reconciledBy;

    @Column(name = "reconcile_note")
    private @Nullable String reconcileNote;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Version
    private @Nullable Long version;

    @Embedded
    private AuditStamps audit = new AuditStamps();

    @SuppressWarnings("NullAway.Init") // JPA
    protected DrawerSession() {}

    /** Opens a drawer with its float (PAY-04). */
    public static DrawerSession open(
            UUID id,
            TenantId tenant,
            UUID registerId,
            @Nullable UUID openedBy,
            BigDecimal openingFloat,
            String currency,
            Instant openedAt) {
        if (openingFloat.signum() < 0) {
            throw new BusinessRuleViolation(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "An opening float is not negative",
                    Map.of("field", "openingFloat"));
        }
        DrawerSession session = new DrawerSession();
        session.id = Objects.requireNonNull(id, "id");
        session.tenantId = tenant.value();
        session.registerId = Objects.requireNonNull(registerId, "registerId");
        session.openedBy = openedBy;
        session.openingFloat = openingFloat;
        session.currency = currency;
        session.openedAt = openedAt;
        return session;
    }

    /** Manual cash movements (paid out, paid in, drops) need an OPEN drawer. */
    public void requireOpen() {
        if (status != DrawerStatus.OPEN) {
            throw stateInvalid();
        }
    }

    /** Sales and refunds land on a drawer until it is reconciled. */
    public boolean takesCash() {
        return status != DrawerStatus.RECONCILED;
    }

    /**
     * Counts the drawer by denomination (PAY-04): values the tenant does not use are refused; the same value twice is
     * added up. A drawer can be counted again until it is reconciled; the last count stands.
     *
     * @param allowed the tenant's denominations
     * @param expected the expected cash now
     */
    public void count(
            Collection<DenominationCount> counted,
            Collection<BigDecimal> allowed,
            BigDecimal expected,
            @Nullable UUID by,
            Instant at) {
        if (status == DrawerStatus.RECONCILED) {
            throw stateInvalid();
        }
        Map<BigDecimal, Integer> byValue = new TreeMap<>(Comparator.reverseOrder());
        for (DenominationCount c : counted) {
            BigDecimal value = normalised(c.value());
            if (allowed.stream().noneMatch(a -> a.compareTo(value) == 0)) {
                throw new BusinessRuleViolation(
                        PaymentsErrorCode.DENOMINATION_UNKNOWN, Map.of("value", value.toPlainString()));
            }
            if (c.count() < 0) {
                throw new BusinessRuleViolation(
                        PlatformErrorCode.VALIDATION_FAILED, "A count is not negative", Map.of("field", "count"));
            }
            byValue.merge(value, c.count(), Integer::sum);
        }
        denominations.clear();
        byValue.forEach((value, count) -> denominations.add(new DenominationCount(value, count)));
        this.countedCash =
                denominations.stream().map(DenominationCount::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        settle(expected);
        this.countedBy = by;
        this.countedAt = at;
        this.status = DrawerStatus.COUNTED;
    }

    /** Reconciles a counted drawer against the expected cash at this moment (PAY-04). */
    public void reconcile(BigDecimal expected, @Nullable UUID by, @Nullable String note, Instant at) {
        if (status != DrawerStatus.COUNTED) {
            throw stateInvalid();
        }
        settle(expected);
        this.reconciledBy = by;
        this.reconcileNote = note;
        this.reconciledAt = at;
        this.status = DrawerStatus.RECONCILED;
    }

    /** Whether {@code user} opened the drawer (PAY-04: a cashier operates their own drawer). */
    public boolean openedBy(@Nullable UUID user) {
        return user != null && user.equals(openedBy);
    }

    private void settle(BigDecimal expected) {
        this.expectedCash = expected;
        this.variance = Objects.requireNonNull(countedCash).subtract(expected);
    }

    private static BigDecimal normalised(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    private BusinessRuleViolation stateInvalid() {
        return new BusinessRuleViolation(PaymentsErrorCode.DRAWER_STATE_INVALID, Map.of("status", status.name()));
    }

    @Override
    public void recordCreation(Instant at, @Nullable UserId by) {
        audit.created(at, by);
    }

    @Override
    public void recordModification(Instant at, @Nullable UserId by) {
        audit.modified(at, by);
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID registerId() {
        return registerId;
    }

    public @Nullable UUID openedByUser() {
        return openedBy;
    }

    public BigDecimal openingFloat() {
        return openingFloat;
    }

    public @Nullable BigDecimal countedCash() {
        return countedCash;
    }

    public @Nullable BigDecimal expectedCash() {
        return expectedCash;
    }

    public @Nullable BigDecimal variance() {
        return variance;
    }

    public List<DenominationCount> denominations() {
        return List.copyOf(denominations);
    }

    public DrawerStatus status() {
        return status;
    }

    public Instant openedAt() {
        return openedAt;
    }

    public @Nullable Instant countedAt() {
        return countedAt;
    }

    public @Nullable Instant reconciledAt() {
        return reconciledAt;
    }

    public @Nullable UUID reconciledBy() {
        return reconciledBy;
    }

    public String currency() {
        return currency;
    }

    public long version() {
        return version == null ? 0 : version;
    }

    public AuditStamps audit() {
        return audit;
    }
}
