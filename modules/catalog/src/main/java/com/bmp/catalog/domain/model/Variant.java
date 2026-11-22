package com.bmp.catalog.domain.model;

import com.bmp.catalog.domain.error.CatalogErrorCode;
import com.bmp.shared.audit.Auditable;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.FieldError;
import com.bmp.shared.error.NotFoundException;
import com.bmp.shared.error.ValidationFailure;
import com.bmp.shared.id.PackagingUnitId;
import com.bmp.shared.id.ProductId;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import com.bmp.shared.id.VariantId;
import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * The specific thing stock, cost and price attach to (Red / Large egg) — aggregate root of its packaging units and
 * their barcodes. Invariants: exactly one base packaging unit with {@code qtyInBase = 1} (CAT-02); every
 * {@code qtyInBase} positive and unit codes unique (CAT-04); every unit draws from one of the variant's stock states
 * (CAT-07). Variants are deactivated, never deleted (CAT-11).
 */
@Entity
@Table(schema = "catalog", name = "variant")
public class Variant implements Auditable {

    /** Scale of {@code qty_in_base} (numeric(20,6)). */
    public static final int QTY_SCALE = 6;

    public static final int MAX_PACKAGING_UNITS = 30;

    private static final BigDecimal MAX_QTY = new BigDecimal("99999999999999.999999");
    private static final Pattern SKU = Pattern.compile("[A-Z0-9][A-Z0-9_.\\-]{0,63}");

    @Id
    private UUID id;

    @org.hibernate.annotations.TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(nullable = false)
    private String sku;

    @Column(nullable = false)
    private String name;

    @Column(name = "stock_states", nullable = false)
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> stockStates = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "sell_mode", nullable = false)
    private SellMode sellMode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CatalogStatus status;

    @ElementCollection
    @CollectionTable(
            schema = "catalog",
            name = "variant_attribute_value",
            joinColumns = @JoinColumn(name = "variant_id"))
    @Column(name = "attribute_value_id", nullable = false)
    private Set<UUID> attributeValueIds = new HashSet<>();

    @OneToMany(cascade = CascadeType.ALL)
    @JoinColumn(name = "variant_id", nullable = false)
    private List<PackagingUnit> packagingUnits = new ArrayList<>();

    /** The catalog version of the last change (sync deltas, CAT-13); set on every change so the version moves. */
    @Column(nullable = false)
    private long revision;

    /** Null until first stored, so Spring Data persists (not merges) new aggregates with assigned ids. */
    @Version
    private @Nullable Long version;

    @Embedded
    private AuditStamps audit = new AuditStamps();

    @SuppressWarnings("NullAway.Init") // JPA
    protected Variant() {}

    private Variant(VariantId id, TenantId tenant, ProductId product, NewVariant spec, Supplier<UUID> ids) {
        this.id = id.value();
        this.tenantId = tenant.value();
        this.productId = product.value();
        this.sku = validSku(spec.sku());
        this.name = spec.name();
        this.stockStates = StockStates.validated(spec.stockStates());
        this.sellMode = spec.sellMode();
        this.status = CatalogStatus.ACTIVE;
        this.attributeValueIds.addAll(spec.attributeValueIds());
        long bases = spec.packagingUnits().stream()
                .filter(PackagingUnitDraft::isBase)
                .count();
        if (bases != 1) {
            // Rule CAT-02: exactly one base packaging unit.
            throw new BusinessRuleViolation(
                    CatalogErrorCode.INVALID_QTY_IN_BASE,
                    "A variant has exactly one base packaging unit",
                    Map.of("baseUnits", bases));
        }
        for (PackagingUnitDraft unit : spec.packagingUnits()) {
            addUnit(unit, ids.get());
        }
        if (packagingUnits.stream().noneMatch(PackagingUnit::isDefaultSell)) {
            packagingUnits.stream()
                    .filter(PackagingUnit::canSell)
                    .min(Comparator.comparing(PackagingUnit::isBase).reversed())
                    .ifPresent(unit -> unit.markDefaultSell(true));
        }
    }

    /**
     * A new variant with its packaging units (one of them the base unit).
     *
     * @param ids supplies the packaging unit ids
     */
    public static Variant create(
            VariantId id, TenantId tenant, ProductId product, NewVariant spec, Supplier<UUID> ids) {
        return new Variant(id, tenant, product, spec, ids);
    }

    /**
     * Rule CAT-04: a quantity in base units at storage scale; positive and within numeric(20,6).
     *
     * @throws BusinessRuleViolation {@code INVALID_QTY_IN_BASE} otherwise
     */
    public static BigDecimal normalizedQty(BigDecimal qty, String unitCode) {
        BigDecimal scaled = qty.setScale(QTY_SCALE, RoundingMode.HALF_UP);
        if (scaled.signum() <= 0 || scaled.compareTo(MAX_QTY) > 0) {
            throw new BusinessRuleViolation(
                    CatalogErrorCode.INVALID_QTY_IN_BASE,
                    "Quantity in base units of " + unitCode + " must be positive",
                    Map.of("unitCode", unitCode, "qtyInBase", qty.toPlainString()));
        }
        return scaled;
    }

    public void rename(String newName) {
        this.name = Objects.requireNonNull(newName, "newName");
    }

    /** Rule CAT-06: SKUs may be overridden; uniqueness per tenant is the store's unique key. */
    public void changeSku(String newSku) {
        this.sku = validSku(newSku);
    }

    /** Changes the stock states; every active packaging unit must keep drawing from one of them (CAT-07). */
    public void changeStockStates(List<String> newStates) {
        List<String> valid = StockStates.validated(newStates);
        for (PackagingUnit unit : packagingUnits) {
            if (unit.isActive()) {
                StockStates.requireIn(valid, unit.stockState(), unit.code());
            }
        }
        stockStates.clear();
        stockStates.addAll(valid);
    }

    public void changeSellMode(SellMode newSellMode) {
        this.sellMode = Objects.requireNonNull(newSellMode, "newSellMode");
    }

    /** Adds a (non-base) packaging unit. */
    public PackagingUnit addPackagingUnit(PackagingUnitDraft draft, UUID unitId) {
        if (draft.isBase()) {
            throw new BusinessRuleViolation(CatalogErrorCode.BASE_UNIT_IMMUTABLE, Map.of("unitCode", draft.code()));
        }
        PackagingUnit unit = addUnit(draft, unitId);
        if (draft.isDefaultSell()) {
            makeDefaultSell(unit);
        }
        return unit;
    }

    /**
     * Changes a packaging unit. The base unit keeps {@code qtyInBase = 1} and stays active (CAT-02); a new size
     * changes future conversions only.
     */
    public PackagingUnit changePackagingUnit(UUID unitId, PackagingUnitChange change) {
        PackagingUnit unit = requireUnit(unitId);
        if (unit.isBase() && (change.resizes() || change.status() == CatalogStatus.INACTIVE)) {
            throw new BusinessRuleViolation(CatalogErrorCode.BASE_UNIT_IMMUTABLE, Map.of("unitCode", unit.code()));
        }
        if (change.name() != null) {
            unit.rename(change.name());
        }
        BigDecimal newQty = change.qtyInBase();
        if (newQty != null) {
            unit.resize(normalizedQty(newQty, unit.code()), change.definedAs());
        }
        String newState = change.stockState();
        if (newState != null) {
            StockStates.requireIn(stockStates, newState, unit.code());
            unit.drawFrom(newState);
        }
        if (change.canBuy() != null || change.canSell() != null) {
            unit.allow(
                    change.canBuy() != null ? change.canBuy() : unit.canBuy(),
                    change.canSell() != null ? change.canSell() : unit.canSell());
        }
        if (change.status() == CatalogStatus.ACTIVE) {
            // Rule CAT-07: a unit becoming active again must draw from one of the variant's current states.
            StockStates.requireIn(stockStates, unit.stockState(), unit.code());
        }
        if (change.status() != null) {
            unit.changeStatus(change.status());
        }
        if (Boolean.TRUE.equals(change.isDefaultSell())) {
            makeDefaultSell(unit);
        } else if (Boolean.FALSE.equals(change.isDefaultSell())) {
            unit.markDefaultSell(false);
        }
        return unit;
    }

    /**
     * Rule CAT-14: recomputes the size of units defined in another unit (the 18 kg can) after a conversion changed.
     *
     * @param converter base units of a defined quantity under the product's current conversions
     * @return the new {@code qtyInBase} of every unit whose size changed
     */
    public Map<UUID, BigDecimal> resizeDefinedUnits(Function<DefinedQuantity, BigDecimal> converter) {
        Map<UUID, BigDecimal> changed = new LinkedHashMap<>();
        for (PackagingUnit unit : packagingUnits) {
            DefinedQuantity defined = unit.definedAs();
            if (defined == null) {
                continue;
            }
            BigDecimal newQty = normalizedQty(converter.apply(defined), unit.code());
            if (newQty.compareTo(unit.qtyInBase()) != 0) {
                unit.resize(newQty, defined);
                changed.put(unit.id(), newQty);
            }
        }
        return changed;
    }

    /** Rule CAT-12: attaches a barcode; uniqueness across the tenant is the store's primary key. */
    public PackagingUnit addBarcode(UUID unitId, String barcode) {
        PackagingUnit unit = requireUnit(unitId);
        boolean taken = packagingUnits.stream().anyMatch(u -> u.barcodes().contains(barcode));
        if (taken || !unit.addBarcode(barcode)) {
            throw new BusinessRuleViolation(CatalogErrorCode.BARCODE_ALREADY_EXISTS, Map.of("barcode", barcode));
        }
        return unit;
    }

    public PackagingUnit removeBarcode(UUID unitId, String barcode) {
        PackagingUnit unit = requireUnit(unitId);
        if (!unit.removeBarcode(barcode)) {
            throw new BusinessRuleViolation(CatalogErrorCode.BARCODE_NOT_FOUND, Map.of("barcode", barcode));
        }
        return unit;
    }

    /** Rule CAT-11: deactivated, never deleted. Returns whether the status changed. */
    public boolean deactivate() {
        boolean changed = status != CatalogStatus.INACTIVE;
        this.status = CatalogStatus.INACTIVE;
        return changed;
    }

    /** Returns whether the status changed. */
    public boolean reactivate() {
        boolean changed = status != CatalogStatus.ACTIVE;
        this.status = CatalogStatus.ACTIVE;
        return changed;
    }

    /** Records the catalog version of this change; every change takes one, so the aggregate version moves too. */
    public void touch(long catalogRevision) {
        this.revision = catalogRevision;
    }

    /** Rule CAT-08: active with at least one active unit that can be sold. */
    public boolean isSellable() {
        return isActive() && packagingUnits.stream().anyMatch(u -> u.isActive() && u.canSell());
    }

    /** Rule CAT-08: active with at least one active unit that can be bought. */
    public boolean isPurchasable() {
        return isActive() && packagingUnits.stream().anyMatch(u -> u.isActive() && u.canBuy());
    }

    public Optional<PackagingUnit> packagingUnit(UUID unitId) {
        return packagingUnits.stream().filter(u -> u.id().equals(unitId)).findFirst();
    }

    public PackagingUnit baseUnit() {
        return packagingUnits.stream()
                .filter(PackagingUnit::isBase)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Variant " + id + " has no base unit"));
    }

    @Override
    public void recordCreation(Instant at, @Nullable UserId by) {
        audit.created(at, by);
    }

    @Override
    public void recordModification(Instant at, @Nullable UserId by) {
        audit.modified(at, by);
    }

    public VariantId id() {
        return VariantId.of(id);
    }

    public ProductId productId() {
        return ProductId.of(productId);
    }

    public String sku() {
        return sku;
    }

    public String name() {
        return name;
    }

    public List<String> stockStates() {
        return List.copyOf(stockStates);
    }

    public SellMode sellMode() {
        return sellMode;
    }

    public CatalogStatus status() {
        return status;
    }

    public boolean isActive() {
        return status == CatalogStatus.ACTIVE;
    }

    public Set<UUID> attributeValueIds() {
        return Set.copyOf(attributeValueIds);
    }

    /** Packaging units, base unit first, then by size and code. */
    public List<PackagingUnit> packagingUnits() {
        return packagingUnits.stream()
                .sorted(Comparator.comparing(PackagingUnit::isBase)
                        .reversed()
                        .thenComparing(PackagingUnit::qtyInBase)
                        .thenComparing(PackagingUnit::code))
                .toList();
    }

    public long revision() {
        return revision;
    }

    public long version() {
        return version != null ? version : 0;
    }

    public Instant createdAt() {
        return audit.createdAt();
    }

    public Instant updatedAt() {
        return audit.updatedAt();
    }

    /** The packaging unit id as a typed id, for other modules. */
    public static PackagingUnitId unitId(PackagingUnit unit) {
        return PackagingUnitId.of(unit.id());
    }

    private PackagingUnit addUnit(PackagingUnitDraft draft, UUID unitId) {
        if (packagingUnits.size() >= MAX_PACKAGING_UNITS) {
            throw new ValidationFailure(List.of(new FieldError(
                    "packagingUnits", "MAX_ITEMS", "a variant has at most " + MAX_PACKAGING_UNITS + " units")));
        }
        if (packagingUnits.stream().anyMatch(u -> u.code().equalsIgnoreCase(draft.code()))) {
            throw new BusinessRuleViolation(
                    CatalogErrorCode.PACKAGING_UNIT_CODE_TAKEN, Map.of("unitCode", draft.code()));
        }
        BigDecimal qty = normalizedQty(draft.qtyInBase(), draft.code());
        if (draft.isBase() && qty.compareTo(BigDecimal.ONE) != 0) {
            throw new BusinessRuleViolation(
                    CatalogErrorCode.INVALID_QTY_IN_BASE,
                    "The base unit's quantity in base units is 1",
                    Map.of("unitCode", draft.code(), "qtyInBase", qty.toPlainString()));
        }
        StockStates.requireIn(stockStates, draft.stockState(), draft.code());
        PackagingUnit unit = new PackagingUnit(
                unitId,
                tenantId,
                new PackagingUnitDraft(
                        draft.code(),
                        draft.name(),
                        qty,
                        draft.definedAs(),
                        draft.stockState(),
                        draft.isBase(),
                        draft.canBuy(),
                        draft.canSell(),
                        draft.isDefaultSell()));
        if (unit.isDefaultSell() && packagingUnits.stream().anyMatch(PackagingUnit::isDefaultSell)) {
            unit.markDefaultSell(false);
        }
        packagingUnits.add(unit);
        return unit;
    }

    private void makeDefaultSell(PackagingUnit unit) {
        packagingUnits.forEach(u -> u.markDefaultSell(u.id().equals(unit.id())));
    }

    private PackagingUnit requireUnit(UUID unitId) {
        return packagingUnit(unitId)
                .orElseThrow(() ->
                        new NotFoundException(CatalogErrorCode.PACKAGING_UNIT_NOT_FOUND, Map.of("unitId", unitId)));
    }

    private static String validSku(String candidate) {
        String upper = candidate.toUpperCase(Locale.ROOT);
        if (!SKU.matcher(upper).matches()) {
            throw new ValidationFailure(List.of(new FieldError("sku", "PATTERN", "must match " + SKU.pattern())));
        }
        return upper;
    }
}
