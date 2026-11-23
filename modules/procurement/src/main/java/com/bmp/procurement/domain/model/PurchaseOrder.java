package com.bmp.procurement.domain.model;

import com.bmp.procurement.domain.error.ProcurementErrorCode;
import com.bmp.shared.audit.Auditable;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import com.bmp.shared.money.RoundingPolicy;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * An order to a supplier (PRO-01): DRAFT while it is prepared, SENT, then PARTIALLY_RECEIVED or RECEIVED as posted
 * receipts come in, or CANCELLED before anything arrived. Lines name buyable packaging units only.
 */
@Entity
@Table(schema = "procurement", name = "purchase_order")
public class PurchaseOrder implements Auditable {

    @Id
    private UUID id;

    @org.hibernate.annotations.TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private String number;

    @Column(name = "supplier_id", nullable = false, updatable = false)
    private UUID supplierId;

    @Column(name = "location_id", nullable = false, updatable = false)
    private UUID locationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(name = "expected_on")
    private @Nullable LocalDate expectedOn;

    private @Nullable String notes;

    @Column(nullable = false, updatable = false)
    private String currency;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "po_id", nullable = false, updatable = false)
    @OrderColumn(name = "line_no")
    private List<OrderLine> lines = new ArrayList<>();

    @Column(name = "sent_at")
    private @Nullable Instant sentAt;

    @Column(name = "cancelled_at")
    private @Nullable Instant cancelledAt;

    @Version
    private @Nullable Long version;

    @Embedded
    private AuditStamps audit = new AuditStamps();

    @SuppressWarnings("NullAway.Init") // JPA
    protected PurchaseOrder() {}

    private PurchaseOrder(UUID id, TenantId tenant, String number, UUID supplierId, UUID locationId, String currency) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = tenant.value();
        this.number = Objects.requireNonNull(number, "number");
        this.supplierId = Objects.requireNonNull(supplierId, "supplierId");
        this.locationId = Objects.requireNonNull(locationId, "locationId");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.status = OrderStatus.DRAFT;
    }

    /** A line to order: so much of a buyable unit at a cost per unit. */
    public record Line(Purchasable unit, BigDecimal qty, BigDecimal unitCost) {}

    /** Rule PRO-01: a DRAFT order. */
    public static PurchaseOrder draft(
            UUID id,
            TenantId tenant,
            String number,
            UUID supplierId,
            UUID locationId,
            String currency,
            @Nullable LocalDate expectedOn,
            @Nullable String notes,
            List<Line> lines,
            Supplier<UUID> ids) {
        PurchaseOrder order = new PurchaseOrder(id, tenant, number, supplierId, locationId, currency);
        order.expectedOn = expectedOn;
        order.notes = notes;
        order.setLines(lines, ids);
        return order;
    }

    public void replaceLines(List<Line> newLines, Supplier<UUID> ids) {
        requireDraft();
        setLines(newLines, ids);
    }

    public void changeExpectedOn(LocalDate date) {
        requireDraft();
        this.expectedOn = date;
    }

    public void changeNotes(String newNotes) {
        requireDraft();
        this.notes = newNotes;
    }

    public void send(Instant at) {
        requireDraft();
        this.status = OrderStatus.SENT;
        this.sentAt = at;
    }

    /** Cancels an order nothing was received for yet. */
    public void cancel(Instant at) {
        boolean received = lines.stream().anyMatch(l -> l.receivedQty().signum() != 0);
        if (status == OrderStatus.CANCELLED || status == OrderStatus.RECEIVED || received) {
            throw invalidState();
        }
        this.status = OrderStatus.CANCELLED;
        this.cancelledAt = at;
    }

    /** Whether receipts may be posted against it. */
    public boolean isOpen() {
        return status == OrderStatus.SENT || status == OrderStatus.PARTIALLY_RECEIVED;
    }

    public java.util.Optional<OrderLine> line(UUID lineId) {
        return lines.stream().filter(l -> l.id().equals(lineId)).findFirst();
    }

    /** Rule PRO-01: a posted receipt adds to the lines' received quantities and moves the status on. */
    public void received(Map<UUID, BigDecimal> qtyByLine) {
        if (!isOpen()) {
            throw invalidState();
        }
        qtyByLine.forEach(
                (lineId, qty) -> line(lineId).orElseThrow(this::mismatch).receive(qty));
        this.status = lines.stream().allMatch(OrderLine::fullyReceived)
                ? OrderStatus.RECEIVED
                : OrderStatus.PARTIALLY_RECEIVED;
    }

    public BigDecimal total(RoundingPolicy rounding) {
        return lines.stream()
                .map(l -> rounding.round(l.qty().multiply(l.unitCost())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void setLines(List<Line> newLines, Supplier<UUID> ids) {
        if (newLines.stream()
                .anyMatch(l -> l.qty().signum() <= 0 || l.unitCost().signum() < 0)) {
            throw new BusinessRuleViolation(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "Ordered quantities are positive and costs are not negative",
                    Map.of("orderId", id));
        }
        lines.clear();
        for (Line line : newLines) {
            lines.add(new OrderLine(
                    ids.get(),
                    tenantId,
                    line.unit().variantId(),
                    line.unit().packagingUnitId(),
                    line.qty(),
                    line.unitCost()));
        }
    }

    private void requireDraft() {
        if (status != OrderStatus.DRAFT) {
            throw invalidState();
        }
    }

    private BusinessRuleViolation invalidState() {
        return new BusinessRuleViolation(
                ProcurementErrorCode.PURCHASE_ORDER_STATE_INVALID, Map.of("status", status.name()));
    }

    private BusinessRuleViolation mismatch() {
        return new BusinessRuleViolation(ProcurementErrorCode.PURCHASE_ORDER_MISMATCH, Map.of("orderId", id));
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

    public String number() {
        return number;
    }

    public UUID supplierId() {
        return supplierId;
    }

    public UUID locationId() {
        return locationId;
    }

    public OrderStatus status() {
        return status;
    }

    public @Nullable LocalDate expectedOn() {
        return expectedOn;
    }

    public @Nullable String notes() {
        return notes;
    }

    public String currency() {
        return currency;
    }

    public List<OrderLine> lines() {
        return List.copyOf(lines);
    }

    public @Nullable Instant sentAt() {
        return sentAt;
    }

    public @Nullable Instant cancelledAt() {
        return cancelledAt;
    }

    public long version() {
        return version != null ? version : 0;
    }

    public AuditStamps audit() {
        return audit;
    }
}
