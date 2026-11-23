package com.bmp.procurement.domain.model;

import com.bmp.procurement.domain.error.ProcurementErrorCode;
import com.bmp.shared.audit.Auditable;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
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
import org.jspecify.annotations.Nullable;

/**
 * A supplier's invoice for one or more posted receipts (PRO-05). Its price variance is what it asks for beyond (or
 * below) the receipts' value; posting makes it final.
 */
@Entity
@Table(schema = "procurement", name = "supplier_invoice")
public class SupplierInvoice implements Auditable {

    @Id
    private UUID id;

    @org.hibernate.annotations.TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "supplier_id", nullable = false, updatable = false)
    private UUID supplierId;

    @Column(name = "supplier_ref", nullable = false, updatable = false)
    private String supplierRef;

    @Column(name = "invoice_date", nullable = false, updatable = false)
    private LocalDate invoiceDate;

    @Column(name = "due_date", nullable = false, updatable = false)
    private LocalDate dueDate;

    @Column(nullable = false, updatable = false)
    private BigDecimal subtotal;

    @Column(nullable = false, updatable = false)
    private BigDecimal tax;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvoiceStatus status;

    @ElementCollection
    @CollectionTable(
            schema = "procurement",
            name = "invoice_receipt_match",
            joinColumns = @JoinColumn(name = "invoice_id"))
    private List<InvoiceMatch> receipts = new ArrayList<>();

    @Column(name = "posted_at")
    private @Nullable Instant postedAt;

    @Version
    private @Nullable Long version;

    @Embedded
    private AuditStamps audit = new AuditStamps();

    @SuppressWarnings("NullAway.Init") // JPA
    protected SupplierInvoice() {}

    @SuppressWarnings("TooManyParameters") // the fields of an invoice
    private SupplierInvoice(
            UUID id,
            TenantId tenant,
            UUID supplierId,
            String supplierRef,
            LocalDate invoiceDate,
            LocalDate dueDate,
            BigDecimal subtotal,
            BigDecimal tax,
            String currency,
            List<InvoiceMatch> receipts) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = tenant.value();
        this.supplierId = supplierId;
        this.supplierRef = supplierRef.strip();
        this.invoiceDate = invoiceDate;
        this.dueDate = dueDate;
        this.subtotal = subtotal;
        this.tax = tax;
        this.currency = currency;
        this.receipts.addAll(receipts);
        this.status = InvoiceStatus.DRAFT;
    }

    @SuppressWarnings("TooManyParameters") // the fields of an invoice
    public static SupplierInvoice draft(
            UUID id,
            TenantId tenant,
            UUID supplierId,
            String supplierRef,
            LocalDate invoiceDate,
            LocalDate dueDate,
            BigDecimal subtotal,
            BigDecimal tax,
            String currency,
            List<InvoiceMatch> receipts) {
        if (receipts.isEmpty()) {
            throw new IllegalArgumentException("An invoice matches at least one receipt");
        }
        return new SupplierInvoice(
                id, tenant, supplierId, supplierRef, invoiceDate, dueDate, subtotal, tax, currency, receipts);
    }

    public void post(Instant at) {
        if (status != InvoiceStatus.DRAFT) {
            throw new BusinessRuleViolation(
                    ProcurementErrorCode.INVOICE_STATE_INVALID, Map.of("status", status.name()));
        }
        this.status = InvoiceStatus.POSTED;
        this.postedAt = at;
    }

    public BigDecimal receiptValue() {
        return receipts.stream().map(InvoiceMatch::receiptValue).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Rule PRO-05: subtotal − receipt value. */
    public BigDecimal priceVariance() {
        return subtotal.subtract(receiptValue());
    }

    public BigDecimal total() {
        return subtotal.add(tax);
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

    public UUID supplierId() {
        return supplierId;
    }

    public String supplierRef() {
        return supplierRef;
    }

    public LocalDate invoiceDate() {
        return invoiceDate;
    }

    public LocalDate dueDate() {
        return dueDate;
    }

    public BigDecimal subtotal() {
        return subtotal;
    }

    public BigDecimal tax() {
        return tax;
    }

    public String currency() {
        return currency;
    }

    public InvoiceStatus status() {
        return status;
    }

    public List<InvoiceMatch> receipts() {
        return List.copyOf(receipts);
    }

    public @Nullable Instant postedAt() {
        return postedAt;
    }

    public long version() {
        return version != null ? version : 0;
    }

    public AuditStamps audit() {
        return audit;
    }
}
