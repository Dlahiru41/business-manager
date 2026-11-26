package com.bmp.sales.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A line of a bill, with its share of the bill discount, its tax and what has been returned of it. */
@Entity
@Table(schema = "sales", name = "bill_line")
public class BillLine {

    @Id
    private UUID id;

    @org.hibernate.annotations.TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "variant_id", nullable = false)
    private UUID variantId;

    @Column(name = "packaging_unit_id", nullable = false)
    private UUID packagingUnitId;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private BigDecimal qty;

    @Column(name = "qty_base", nullable = false)
    private BigDecimal qtyBase;

    @Column(name = "stock_state", nullable = false)
    private String stockState;

    @Column(nullable = false)
    private boolean weighed;

    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice;

    @Column(name = "line_discount", nullable = false)
    private BigDecimal lineDiscount;

    @Column(name = "rule_discount", nullable = false)
    private BigDecimal ruleDiscount;

    @Column(name = "allocated_bill_discount", nullable = false)
    private BigDecimal allocatedBillDiscount;

    @Column(name = "tax_amount", nullable = false)
    private BigDecimal taxAmount;

    @Column(name = "line_total", nullable = false)
    private BigDecimal lineTotal;

    @Column(name = "price_source")
    private @Nullable String priceSource;

    @Column(name = "returned_qty", nullable = false)
    private BigDecimal returnedQty = BigDecimal.ZERO;

    @Column(name = "returned_amount", nullable = false)
    private BigDecimal returnedAmount = BigDecimal.ZERO;

    @Column(name = "returned_tax", nullable = false)
    private BigDecimal returnedTax = BigDecimal.ZERO;

    @SuppressWarnings("NullAway.Init") // JPA
    protected BillLine() {}

    @SuppressWarnings("TooManyParameters") // a line's figures
    BillLine(UUID tenantId, LineDraft draft, BigDecimal allocated, BigDecimal tax, BigDecimal total) {
        this.id = Objects.requireNonNull(draft.id(), "id");
        this.tenantId = tenantId;
        this.variantId = draft.variantId();
        this.packagingUnitId = draft.packagingUnitId();
        this.description = draft.description();
        this.qty = draft.qty();
        this.qtyBase = draft.qtyBase();
        this.stockState = draft.stockState();
        this.weighed = draft.weighed();
        this.unitPrice = draft.unitPrice();
        this.lineDiscount = draft.lineDiscount();
        this.ruleDiscount = draft.ruleDiscount();
        this.allocatedBillDiscount = allocated;
        this.taxAmount = tax;
        this.lineTotal = total;
        this.priceSource = draft.priceSource();
    }

    void returned(BigDecimal qty, BigDecimal amount, BigDecimal tax) {
        this.returnedQty = returnedQty.add(qty);
        this.returnedAmount = returnedAmount.add(amount);
        this.returnedTax = returnedTax.add(tax);
    }

    public BigDecimal remainingQty() {
        return qty.subtract(returnedQty);
    }

    public UUID id() {
        return id;
    }

    public UUID variantId() {
        return variantId;
    }

    public UUID packagingUnitId() {
        return packagingUnitId;
    }

    public String description() {
        return description;
    }

    public BigDecimal qty() {
        return qty;
    }

    public BigDecimal qtyBase() {
        return qtyBase;
    }

    public String stockState() {
        return stockState;
    }

    public boolean weighed() {
        return weighed;
    }

    public BigDecimal unitPrice() {
        return unitPrice;
    }

    public BigDecimal lineDiscount() {
        return lineDiscount;
    }

    /** What price rules took off; included in {@link #lineDiscount()}. */
    public BigDecimal ruleDiscount() {
        return ruleDiscount;
    }

    public BigDecimal allocatedBillDiscount() {
        return allocatedBillDiscount;
    }

    public BigDecimal taxAmount() {
        return taxAmount;
    }

    public BigDecimal lineTotal() {
        return lineTotal;
    }

    public @Nullable String priceSource() {
        return priceSource;
    }

    public BigDecimal returnedQty() {
        return returnedQty;
    }

    public BigDecimal returnedAmount() {
        return returnedAmount;
    }

    public BigDecimal returnedTax() {
        return returnedTax;
    }
}
