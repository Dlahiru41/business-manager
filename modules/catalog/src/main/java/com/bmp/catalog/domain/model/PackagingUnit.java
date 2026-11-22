package com.bmp.catalog.domain.model;

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
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A buy and/or sell unit of a variant, defined as a multiple of the base unit ({@code tray = 30 piece}, {@code can =
 * 19,565.28 ml}). Part of the {@link Variant} aggregate, which enforces its rules (CAT-02, CAT-04, CAT-07, CAT-12).
 */
@Entity
@Table(schema = "catalog", name = "packaging_unit")
public class PackagingUnit {

    @Id
    private UUID id;

    @org.hibernate.annotations.TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "qty_in_base", nullable = false, precision = 20, scale = 6)
    private BigDecimal qtyInBase;

    @Embedded
    private @Nullable DefinedQuantity definedAs;

    @Column(name = "stock_state", nullable = false)
    private String stockState;

    @Column(name = "is_base", nullable = false, updatable = false)
    private boolean base;

    @Column(name = "can_buy", nullable = false)
    private boolean canBuy;

    @Column(name = "can_sell", nullable = false)
    private boolean canSell;

    @Column(name = "is_default_sell", nullable = false)
    private boolean defaultSell;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CatalogStatus status;

    @ElementCollection
    @CollectionTable(schema = "catalog", name = "barcode", joinColumns = @JoinColumn(name = "packaging_unit_id"))
    @Column(name = "code", nullable = false)
    private Set<String> barcodes = new HashSet<>();

    @SuppressWarnings("NullAway.Init") // JPA
    protected PackagingUnit() {}

    PackagingUnit(UUID id, UUID tenantId, PackagingUnitDraft draft) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.code = draft.code();
        this.name = draft.name();
        this.qtyInBase = draft.qtyInBase();
        this.definedAs = draft.definedAs();
        this.stockState = draft.stockState();
        this.base = draft.isBase();
        this.canBuy = draft.canBuy();
        this.canSell = draft.canSell();
        this.defaultSell = draft.isDefaultSell();
        this.status = CatalogStatus.ACTIVE;
    }

    void rename(String newName) {
        this.name = Objects.requireNonNull(newName, "newName");
    }

    void resize(BigDecimal newQtyInBase, @Nullable DefinedQuantity newDefinedAs) {
        this.qtyInBase = newQtyInBase;
        this.definedAs = newDefinedAs;
    }

    void drawFrom(String newStockState) {
        this.stockState = newStockState;
    }

    void allow(boolean buy, boolean sell) {
        this.canBuy = buy;
        this.canSell = sell;
    }

    void markDefaultSell(boolean isDefault) {
        this.defaultSell = isDefault;
    }

    void changeStatus(CatalogStatus newStatus) {
        this.status = newStatus;
    }

    boolean addBarcode(String barcode) {
        return barcodes.add(barcode);
    }

    boolean removeBarcode(String barcode) {
        return barcodes.remove(barcode);
    }

    public UUID id() {
        return id;
    }

    public String code() {
        return code;
    }

    public String name() {
        return name;
    }

    public BigDecimal qtyInBase() {
        return qtyInBase;
    }

    public @Nullable DefinedQuantity definedAs() {
        return definedAs;
    }

    public String stockState() {
        return stockState;
    }

    public boolean isBase() {
        return base;
    }

    public boolean canBuy() {
        return canBuy;
    }

    public boolean canSell() {
        return canSell;
    }

    public boolean isDefaultSell() {
        return defaultSell;
    }

    public CatalogStatus status() {
        return status;
    }

    public boolean isActive() {
        return status == CatalogStatus.ACTIVE;
    }

    /** Barcodes in a stable order. */
    public List<String> barcodes() {
        return barcodes.stream().sorted().toList();
    }

    /** The quantity in base units of {@code qty} of this unit (e.g. 2 trays → 60 pieces). */
    public BigDecimal toBase(BigDecimal qty) {
        return qty.multiply(qtyInBase);
    }
}
