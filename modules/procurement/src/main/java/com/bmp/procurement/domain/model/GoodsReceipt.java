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
import java.math.RoundingMode;
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
 * A delivery received, with or without an order (PRO-02, PRO-03). Posting makes it final: a correction is a return
 * receipt with negative lines that names the receipt it corrects.
 */
@Entity
@Table(schema = "procurement", name = "goods_receipt")
public class GoodsReceipt implements Auditable {

    private static final int QTY_SCALE = 6;

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

    @Column(name = "po_id", updatable = false)
    private @Nullable UUID purchaseOrderId;

    @Column(name = "corrects_receipt_id", updatable = false)
    private @Nullable UUID correctsReceiptId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReceiptStatus status;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    private @Nullable String notes;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Column(nullable = false)
    private boolean invoiced;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "receipt_id", nullable = false, updatable = false)
    @OrderColumn(name = "line_no")
    private List<ReceiptLine> lines = new ArrayList<>();

    @Column(name = "posted_at")
    private @Nullable Instant postedAt;

    @Column(name = "posted_by")
    private @Nullable UUID postedBy;

    @Version
    private @Nullable Long version;

    @Embedded
    private AuditStamps audit = new AuditStamps();

    @SuppressWarnings("NullAway.Init") // JPA
    protected GoodsReceipt() {}

    private GoodsReceipt(
            UUID id,
            TenantId tenant,
            String number,
            UUID supplierId,
            UUID locationId,
            @Nullable UUID purchaseOrderId,
            @Nullable UUID correctsReceiptId,
            Instant receivedAt,
            String currency) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = tenant.value();
        this.number = Objects.requireNonNull(number, "number");
        this.supplierId = Objects.requireNonNull(supplierId, "supplierId");
        this.locationId = Objects.requireNonNull(locationId, "locationId");
        this.purchaseOrderId = purchaseOrderId;
        this.correctsReceiptId = correctsReceiptId;
        this.receivedAt = receivedAt;
        this.currency = currency;
        this.status = ReceiptStatus.DRAFT;
    }

    /**
     * A line as received.
     *
     * @param qty in the unit; negative on a return
     * @param unitCost per packaging unit
     */
    public record Line(
            @Nullable UUID purchaseOrderLineId,
            Purchasable unit,
            BigDecimal qty,
            BigDecimal unitCost,
            @Nullable String batchCode,
            @Nullable LocalDate expiresOn) {}

    /** Rule PRO-02: a DRAFT receipt; all lines positive (a delivery) or all negative (a return). */
    @SuppressWarnings("TooManyParameters") // the header of a receipt and its lines
    public static GoodsReceipt draft(
            UUID id,
            TenantId tenant,
            String number,
            UUID supplierId,
            UUID locationId,
            @Nullable UUID purchaseOrderId,
            @Nullable UUID correctsReceiptId,
            Instant receivedAt,
            String currency,
            @Nullable String notes,
            List<Line> lines,
            RoundingPolicy rounding,
            Supplier<UUID> ids) {
        GoodsReceipt receipt = new GoodsReceipt(
                id, tenant, number, supplierId, locationId, purchaseOrderId, correctsReceiptId, receivedAt, currency);
        receipt.notes = notes;
        receipt.setLines(lines, rounding, ids);
        return receipt;
    }

    public void replaceLines(List<Line> newLines, RoundingPolicy rounding, Supplier<UUID> ids) {
        requireDraft();
        setLines(newLines, rounding, ids);
    }

    public void changeReceivedAt(Instant at) {
        requireDraft();
        this.receivedAt = at;
    }

    public void changeNotes(String newNotes) {
        requireDraft();
        this.notes = newNotes;
    }

    /** Rule PRO-02: posting makes the receipt final. */
    public void post(Instant at, @Nullable UUID by) {
        requireDraft();
        this.status = ReceiptStatus.POSTED;
        this.postedAt = at;
        this.postedBy = by;
    }

    public void cancel() {
        requireDraft();
        this.status = ReceiptStatus.CANCELLED;
    }

    /** Rule PRO-05: a posted receipt is matched to one invoice. */
    public void markInvoiced() {
        if (status != ReceiptStatus.POSTED) {
            throw new BusinessRuleViolation(ProcurementErrorCode.RECEIPT_NOT_POSTED, Map.of("receiptId", id));
        }
        if (invoiced) {
            throw new BusinessRuleViolation(ProcurementErrorCode.RECEIPT_ALREADY_INVOICED, Map.of("receiptId", id));
        }
        this.invoiced = true;
    }

    /** A return to the supplier: negative lines. */
    public boolean isReturn() {
        return !lines.isEmpty() && lines.getFirst().qty().signum() < 0;
    }

    public BigDecimal total() {
        return lines.stream().map(ReceiptLine::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Rule PRO-02: a return sends back only what the corrected receipt brought in, less what earlier returns sent
     * back, per variant in base units.
     */
    public void requireReturnableFrom(GoodsReceipt corrected, List<GoodsReceipt> earlierReturns) {
        if (!isReturn()
                || corrected.status != ReceiptStatus.POSTED
                || corrected.isReturn()
                || !corrected.supplierId.equals(supplierId)
                || !corrected.id.equals(correctsReceiptId)) {
            throw new BusinessRuleViolation(
                    ProcurementErrorCode.CORRECTION_TARGET_INVALID, Map.of("correctsReceiptId", corrected.id));
        }
        Map<UUID, BigDecimal> left = new java.util.HashMap<>();
        corrected.lines.forEach(l -> left.merge(l.variantId(), l.qtyBase(), BigDecimal::add));
        earlierReturns.stream()
                .filter(r -> !r.id.equals(id))
                .flatMap(r -> r.lines.stream())
                .forEach(l -> left.merge(l.variantId(), l.qtyBase(), BigDecimal::add));
        lines.forEach(l -> left.merge(l.variantId(), l.qtyBase(), BigDecimal::add));
        lines.stream()
                .map(ReceiptLine::variantId)
                .filter(variant -> !corrected.lines.stream()
                                .map(ReceiptLine::variantId)
                                .toList()
                                .contains(variant)
                        || left.getOrDefault(variant, BigDecimal.ZERO).signum() < 0)
                .findFirst()
                .ifPresent(variant -> {
                    throw new BusinessRuleViolation(
                            ProcurementErrorCode.CORRECTION_TARGET_INVALID,
                            "A return sends back at most what the corrected receipt received",
                            Map.of("correctsReceiptId", corrected.id, "variantId", variant));
                });
    }

    private void setLines(List<Line> newLines, RoundingPolicy rounding, Supplier<UUID> ids) {
        long negative = newLines.stream().filter(l -> l.qty().signum() < 0).count();
        if ((negative != 0 && negative != newLines.size())
                || newLines.stream().anyMatch(l -> l.qty().signum() == 0)) {
            throw new BusinessRuleViolation(ProcurementErrorCode.MIXED_LINE_SIGNS, Map.of("receiptId", id));
        }
        if ((negative > 0) != (correctsReceiptId != null)) {
            throw new BusinessRuleViolation(ProcurementErrorCode.CORRECTION_TARGET_INVALID, Map.of("receiptId", id));
        }
        if (newLines.stream().anyMatch(l -> l.unitCost().signum() < 0)) {
            throw new BusinessRuleViolation(
                    PlatformErrorCode.VALIDATION_FAILED, "Unit costs are not negative", Map.of("receiptId", id));
        }
        lines.clear();
        for (Line line : newLines) {
            BigDecimal qtyInBase = line.unit().qtyInBase();
            lines.add(new ReceiptLine(
                    ids.get(),
                    tenantId,
                    line.purchaseOrderLineId(),
                    line.unit(),
                    line.qty(),
                    line.qty().multiply(qtyInBase).setScale(QTY_SCALE, RoundingMode.HALF_UP),
                    line.unitCost(),
                    line.unitCost().divide(qtyInBase, QTY_SCALE, RoundingMode.HALF_UP),
                    rounding.round(line.qty().multiply(line.unitCost())),
                    line.batchCode(),
                    line.expiresOn()));
        }
    }

    private void requireDraft() {
        if (status != ReceiptStatus.DRAFT) {
            throw new BusinessRuleViolation(
                    ProcurementErrorCode.GOODS_RECEIPT_STATE_INVALID, Map.of("status", status.name()));
        }
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

    public @Nullable UUID purchaseOrderId() {
        return purchaseOrderId;
    }

    public @Nullable UUID correctsReceiptId() {
        return correctsReceiptId;
    }

    public ReceiptStatus status() {
        return status;
    }

    public Instant receivedAt() {
        return receivedAt;
    }

    public @Nullable String notes() {
        return notes;
    }

    public String currency() {
        return currency;
    }

    public boolean invoiced() {
        return invoiced;
    }

    public List<ReceiptLine> lines() {
        return List.copyOf(lines);
    }

    public @Nullable Instant postedAt() {
        return postedAt;
    }

    public @Nullable UUID postedBy() {
        return postedBy;
    }

    public long version() {
        return version != null ? version : 0;
    }

    public AuditStamps audit() {
        return audit;
    }
}
