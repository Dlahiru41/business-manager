package com.bmp.procurement.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** A line of a purchase order in a buyable packaging unit (PRO-01). */
@Entity
@Table(schema = "procurement", name = "purchase_order_line")
public class OrderLine {

    @Id
    private UUID id;

    @org.hibernate.annotations.TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "variant_id", nullable = false, updatable = false)
    private UUID variantId;

    @Column(name = "packaging_unit_id", nullable = false, updatable = false)
    private UUID packagingUnitId;

    @Column(nullable = false, updatable = false)
    private BigDecimal qty;

    @Column(name = "unit_cost", nullable = false, updatable = false)
    private BigDecimal unitCost;

    @Column(name = "received_qty", nullable = false)
    private BigDecimal receivedQty;

    @SuppressWarnings("NullAway.Init") // JPA
    protected OrderLine() {}

    OrderLine(UUID id, UUID tenantId, UUID variantId, UUID packagingUnitId, BigDecimal qty, BigDecimal unitCost) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = tenantId;
        this.variantId = variantId;
        this.packagingUnitId = packagingUnitId;
        this.qty = qty;
        this.unitCost = unitCost;
        this.receivedQty = BigDecimal.ZERO;
    }

    void receive(BigDecimal received) {
        this.receivedQty = receivedQty.add(received);
    }

    public boolean fullyReceived() {
        return receivedQty.compareTo(qty) >= 0;
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

    public BigDecimal qty() {
        return qty;
    }

    public BigDecimal unitCost() {
        return unitCost;
    }

    public BigDecimal receivedQty() {
        return receivedQty;
    }
}
