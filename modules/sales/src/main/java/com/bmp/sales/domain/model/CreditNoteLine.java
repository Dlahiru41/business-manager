package com.bmp.sales.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/** A returned quantity of a bill line with its share of the line total (SAL-10). */
@Entity
@Table(schema = "sales", name = "credit_note_line")
public class CreditNoteLine {

    @Id
    private UUID id;

    @org.hibernate.annotations.TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "bill_line_id", nullable = false, updatable = false)
    private UUID billLineId;

    @Column(name = "variant_id", nullable = false, updatable = false)
    private UUID variantId;

    @Column(name = "stock_state", nullable = false, updatable = false)
    private String stockState;

    @Column(nullable = false, updatable = false)
    private BigDecimal qty;

    @Column(name = "qty_base", nullable = false, updatable = false)
    private BigDecimal qtyBase;

    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "tax_amount", nullable = false, updatable = false)
    private BigDecimal taxAmount;

    @Column(nullable = false, updatable = false)
    private boolean restock;

    @SuppressWarnings("NullAway.Init") // JPA
    protected CreditNoteLine() {}

    @SuppressWarnings("TooManyParameters") // a returned quantity's figures
    CreditNoteLine(
            UUID id,
            UUID tenantId,
            BillLine line,
            BigDecimal qty,
            BigDecimal qtyBase,
            BigDecimal amount,
            BigDecimal taxAmount,
            boolean restock) {
        this.id = id;
        this.tenantId = tenantId;
        this.billLineId = line.id();
        this.variantId = line.variantId();
        this.stockState = line.stockState();
        this.qty = qty;
        this.qtyBase = qtyBase;
        this.amount = amount;
        this.taxAmount = taxAmount;
        this.restock = restock;
    }

    public UUID id() {
        return id;
    }

    public UUID billLineId() {
        return billLineId;
    }

    public UUID variantId() {
        return variantId;
    }

    public String stockState() {
        return stockState;
    }

    public BigDecimal qty() {
        return qty;
    }

    public BigDecimal qtyBase() {
        return qtyBase;
    }

    public BigDecimal amount() {
        return amount;
    }

    public BigDecimal taxAmount() {
        return taxAmount;
    }

    public boolean restock() {
        return restock;
    }
}
