package com.bmp.inventory.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A line of a stock count: the system quantity when it was first counted, and what was counted (INV-12). */
@Entity
@Table(schema = "inventory", name = "stock_count_line")
public class StockCountLine {

    @Id
    private UUID id;

    @org.hibernate.annotations.TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "variant_id", nullable = false, updatable = false)
    private UUID variantId;

    @Column(name = "stock_state", nullable = false, updatable = false)
    private String stockState;

    @Column(name = "batch_id", nullable = false, updatable = false)
    private UUID batchId;

    @Column(name = "system_qty", nullable = false, updatable = false)
    private BigDecimal systemQty;

    @Column(name = "counted_qty", nullable = false)
    private BigDecimal countedQty;

    @Column(name = "variance_qty")
    private @Nullable BigDecimal varianceQty;

    @Column(name = "variance_cost")
    private @Nullable BigDecimal varianceCost;

    @Column(name = "variance_reason")
    private @Nullable String varianceReason;

    @Column(name = "snapshot_at", nullable = false, updatable = false)
    private Instant snapshotAt;

    @SuppressWarnings("NullAway.Init") // JPA
    protected StockCountLine() {}

    StockCountLine(
            UUID id,
            UUID tenantId,
            BalanceKey key,
            BigDecimal systemQty,
            BigDecimal countedQty,
            @Nullable String reason,
            Instant snapshotAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = tenantId;
        this.variantId = key.variantId();
        this.stockState = key.stockState();
        this.batchId = key.batchId();
        this.systemQty = systemQty;
        this.countedQty = countedQty;
        this.varianceReason = reason;
        this.snapshotAt = snapshotAt;
    }

    boolean counts(UUID variant, String state, UUID batch) {
        return variantId.equals(variant) && stockState.equals(state) && batchId.equals(batch);
    }

    void recount(BigDecimal counted, @Nullable String reason) {
        this.countedQty = counted;
        if (reason != null) {
            this.varianceReason = reason;
        }
    }

    /**
     * Rule INV-12: what was counted against what the system held when the line was first counted. Movements since then
     * are in both the physical stock and the balance, so they cancel out.
     */
    public BigDecimal variance() {
        return countedQty.subtract(systemQty);
    }

    void posted(BigDecimal cost) {
        this.varianceQty = variance();
        this.varianceCost = cost;
    }

    public UUID id() {
        return id;
    }

    public BalanceKey key(UUID locationId) {
        return new BalanceKey(variantId, locationId, stockState, batchId);
    }

    public UUID variantId() {
        return variantId;
    }

    public String stockState() {
        return stockState;
    }

    public UUID batchId() {
        return batchId;
    }

    public BigDecimal systemQty() {
        return systemQty;
    }

    public BigDecimal countedQty() {
        return countedQty;
    }

    public @Nullable BigDecimal varianceQty() {
        return varianceQty;
    }

    public @Nullable BigDecimal varianceCost() {
        return varianceCost;
    }

    public @Nullable String varianceReason() {
        return varianceReason;
    }

    public Instant snapshotAt() {
        return snapshotAt;
    }
}
