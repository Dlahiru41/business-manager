package com.bmp.inventory.domain.model;

import com.bmp.inventory.domain.error.InventoryErrorCode;
import com.bmp.shared.error.BusinessRuleViolation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A variant as inventory knows it: a replica of the catalog's snapshot events (newest {@code aggregateVersion} wins).
 *
 * @param stockStates the states its stock is kept in (CAT-07)
 * @param sellMode PREPACKED or BREAK_ON_SALE (INV-06)
 * @param trackBatches receipts need a batch (INV-14)
 * @param trackExpiry receipts need an expiry date (INV-14)
 */
public record StockItem(
        UUID variantId,
        UUID productId,
        String sku,
        String name,
        String baseUom,
        List<String> stockStates,
        String sellMode,
        List<StockUnit> units,
        boolean trackBatches,
        boolean trackExpiry,
        boolean decimalQty,
        boolean active,
        long aggregateVersion) {

    /** Scale of quantities in base units (numeric(20,6)). */
    public static final int QTY_SCALE = 6;

    public StockItem {
        stockStates = List.copyOf(stockStates);
        units = List.copyOf(units);
    }

    public Optional<StockUnit> unit(UUID unitId) {
        return units.stream().filter(u -> u.id().equals(unitId)).findFirst();
    }

    public Optional<StockUnit> baseUnit() {
        return units.stream().filter(StockUnit::isBase).findFirst();
    }

    public boolean hasState(@Nullable String state) {
        return state != null && stockStates.contains(state);
    }

    /**
     * Rules INV-05 and INV-06: a quantity counted in a packaging unit (the base unit when none is given) becomes base
     * units, kept in the unit's stock state unless one is named. A 750 ml bottle of BREAK_ON_SALE oil is 750 ml of OPEN
     * oil; a PREPACKED packet is a packet of its own state.
     */
    public ResolvedQuantity resolve(@Nullable UUID packagingUnitId, @Nullable String stockState, BigDecimal qty) {
        StockUnit unit = packagingUnitId == null
                ? baseUnit().orElseThrow(() -> unknownUnit(null))
                : unit(packagingUnitId).orElseThrow(() -> unknownUnit(packagingUnitId));
        String state = stockState != null ? stockState : unit.stockState();
        if (!hasState(state)) {
            throw new BusinessRuleViolation(
                    InventoryErrorCode.INVALID_STOCK_STATE, Map.of("variantId", variantId, "stockState", state));
        }
        return new ResolvedQuantity(qty.multiply(unit.qtyInBase()).setScale(QTY_SCALE, RoundingMode.HALF_UP), state);
    }

    private BusinessRuleViolation unknownUnit(@Nullable UUID unitId) {
        return new BusinessRuleViolation(
                InventoryErrorCode.UNKNOWN_PACKAGING_UNIT,
                Map.of("variantId", variantId, "packagingUnitId", unitId == null ? "base" : unitId));
    }
}
