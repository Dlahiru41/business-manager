package com.bmp.procurement.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A line of a goods receipt (PRO-03): its quantity and cost in the packaging unit and per base unit.
 * {@code qtyBase = qty × qtyInBase}, {@code unitCostBase = unitCost / qtyInBase} (scale 6), {@code lineTotal = qty ×
 * unitCost} rounded per the tenant policy. Negative on a return.
 */
@Entity
@Table(schema = "procurement", name = "goods_receipt_line")
public class ReceiptLine {

    @Id
    private UUID id;

    @org.hibernate.annotations.TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "po_line_id", updatable = false)
    private @Nullable UUID purchaseOrderLineId;

    @Column(name = "variant_id", nullable = false, updatable = false)
    private UUID variantId;

    @Column(name = "packaging_unit_id", nullable = false, updatable = false)
    private UUID packagingUnitId;

    @Column(nullable = false, updatable = false)
    private BigDecimal qty;

    @Column(name = "qty_base", nullable = false, updatable = false)
    private BigDecimal qtyBase;

    @Column(name = "stock_state", nullable = false, updatable = false)
    private String stockState;

    @Column(name = "unit_cost", nullable = false, updatable = false)
    private BigDecimal unitCost;

    @Column(name = "unit_cost_base", nullable = false, updatable = false)
    private BigDecimal unitCostBase;

    @Column(name = "line_total", nullable = false, updatable = false)
    private BigDecimal lineTotal;

    @Column(name = "batch_code", updatable = false)
    private @Nullable String batchCode;

    @Column(name = "expires_on", updatable = false)
    private @Nullable LocalDate expiresOn;

    @SuppressWarnings("NullAway.Init") // JPA
    protected ReceiptLine() {}

    @SuppressWarnings("TooManyParameters") // the columns of one line
    ReceiptLine(
            UUID id,
            UUID tenantId,
            @Nullable UUID purchaseOrderLineId,
            Purchasable unit,
            BigDecimal qty,
            BigDecimal qtyBase,
            BigDecimal unitCost,
            BigDecimal unitCostBase,
            BigDecimal lineTotal,
            @Nullable String batchCode,
            @Nullable LocalDate expiresOn) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = tenantId;
        this.purchaseOrderLineId = purchaseOrderLineId;
        this.variantId = unit.variantId();
        this.packagingUnitId = unit.packagingUnitId();
        this.stockState = unit.stockState();
        this.qty = qty;
        this.qtyBase = qtyBase;
        this.unitCost = unitCost;
        this.unitCostBase = unitCostBase;
        this.lineTotal = lineTotal;
        this.batchCode = batchCode;
        this.expiresOn = expiresOn;
    }

    public UUID id() {
        return id;
    }

    public @Nullable UUID purchaseOrderLineId() {
        return purchaseOrderLineId;
    }

    public UUID variantId() {
        return variantId;
    }

    public UUID packagingUnitId() {
        return packagingUnitId;
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

    public BigDecimal unitCost() {
        return unitCost;
    }

    public BigDecimal unitCostBase() {
        return unitCostBase;
    }

    public BigDecimal lineTotal() {
        return lineTotal;
    }

    public @Nullable String batchCode() {
        return batchCode;
    }

    public @Nullable LocalDate expiresOn() {
        return expiresOn;
    }
}
