package com.bmp.sales.domain.model;

import com.bmp.shared.audit.Auditable;
import com.bmp.shared.id.UserId;
import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A return document: the returned lines of a bill and how the money goes back (SAL-10). Never changes. */
@Entity
@Table(schema = "sales", name = "credit_note")
public class CreditNote implements Auditable {

    @Id
    private UUID id;

    @org.hibernate.annotations.TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private String number;

    @Column(name = "bill_id", nullable = false, updatable = false)
    private UUID billId;

    @Column(nullable = false, updatable = false)
    private String reason;

    @Column(nullable = false, updatable = false)
    private BigDecimal total;

    @Column(name = "tax_total", nullable = false, updatable = false)
    private BigDecimal taxTotal;

    @Column(nullable = false)
    private String status = "ISSUED";

    @Column(name = "approved_by", updatable = false)
    private @Nullable UUID approvedBy;

    @Column(name = "returned_at", nullable = false, updatable = false)
    private Instant returnedAt;

    @OneToMany(cascade = CascadeType.ALL)
    @JoinColumn(name = "credit_note_id", nullable = false, updatable = false)
    @OrderColumn(name = "line_no")
    private List<CreditNoteLine> lines = new ArrayList<>();

    @ElementCollection
    @CollectionTable(schema = "sales", name = "credit_note_refund", joinColumns = @JoinColumn(name = "credit_note_id"))
    @OrderColumn(name = "refund_no")
    private List<RefundTender> refundTenders = new ArrayList<>();

    @Embedded
    private AuditStamps audit = new AuditStamps();

    @SuppressWarnings("NullAway.Init") // JPA
    protected CreditNote() {}

    @SuppressWarnings("TooManyParameters") // the document's fields
    CreditNote(
            UUID id,
            UUID tenantId,
            String number,
            UUID billId,
            String reason,
            BigDecimal total,
            BigDecimal taxTotal,
            List<CreditNoteLine> lines,
            List<RefundTender> refunds,
            @Nullable UUID approvedBy,
            Instant returnedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = tenantId;
        this.number = number;
        this.billId = billId;
        this.reason = reason;
        this.total = total;
        this.taxTotal = taxTotal;
        this.lines.addAll(lines);
        this.refundTenders.addAll(refunds);
        this.approvedBy = approvedBy;
        this.returnedAt = returnedAt;
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

    public UUID billId() {
        return billId;
    }

    public String reason() {
        return reason;
    }

    public BigDecimal total() {
        return total;
    }

    public BigDecimal taxTotal() {
        return taxTotal;
    }

    public @Nullable UUID approvedBy() {
        return approvedBy;
    }

    public Instant returnedAt() {
        return returnedAt;
    }

    public List<CreditNoteLine> lines() {
        return List.copyOf(lines);
    }

    public List<RefundTender> refundTenders() {
        return List.copyOf(refundTenders);
    }

    public AuditStamps audit() {
        return audit;
    }
}
