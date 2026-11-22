package com.bmp.catalog.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** One input or output of a transformation definition, per run, in base units. Part of the definition aggregate. */
@Entity
@Table(schema = "catalog", name = "transformation_line")
public class TransformationLine {

    @Id
    private UUID id;

    @org.hibernate.annotations.TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Direction direction;

    @Column(name = "variant_id", nullable = false)
    private UUID variantId;

    @Column(name = "stock_state", nullable = false)
    private String stockState;

    @Column(name = "qty_base", nullable = false, precision = 20, scale = 6)
    private BigDecimal qtyBase;

    @Column(name = "packaging_unit_id")
    private @Nullable UUID packagingUnitId;

    @Column(precision = 20, scale = 6)
    private @Nullable BigDecimal qty;

    @Column(name = "cost_share_pct", precision = 6, scale = 3)
    private @Nullable BigDecimal costSharePct;

    @SuppressWarnings("NullAway.Init") // JPA
    protected TransformationLine() {}

    TransformationLine(UUID id, UUID tenantId, int lineNo, LineDraft draft) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.lineNo = lineNo;
        this.direction = draft.direction();
        this.variantId = draft.variantId();
        this.stockState = draft.stockState();
        this.qtyBase = draft.qtyBase();
        this.packagingUnitId = draft.packagingUnitId();
        this.qty = draft.qty();
        this.costSharePct = draft.costSharePct();
    }

    /** Re-derives {@code qtyBase} from the packaging unit's size; returns whether it changed. */
    boolean resize(BigDecimal unitQtyInBase) {
        BigDecimal units = qty != null ? qty : BigDecimal.ONE;
        BigDecimal newQty = Variant.normalizedQty(units.multiply(unitQtyInBase), "line " + lineNo);
        boolean changed = newQty.compareTo(qtyBase) != 0;
        this.qtyBase = newQty;
        return changed;
    }

    /** The same line as a draft, e.g. for an OUT line that should follow a resized IN line. */
    LineDraft toDraft() {
        return new LineDraft(direction, variantId, stockState, qtyBase, packagingUnitId, qty, costSharePct);
    }

    public UUID id() {
        return id;
    }

    public int lineNo() {
        return lineNo;
    }

    public Direction direction() {
        return direction;
    }

    public UUID variantId() {
        return variantId;
    }

    public String stockState() {
        return stockState;
    }

    public BigDecimal qtyBase() {
        return qtyBase;
    }

    public @Nullable UUID packagingUnitId() {
        return packagingUnitId;
    }

    public @Nullable BigDecimal qty() {
        return qty;
    }

    public @Nullable BigDecimal costSharePct() {
        return costSharePct;
    }
}
