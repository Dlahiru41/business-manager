package com.bmp.catalog.domain.model;

import java.math.BigDecimal;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A packaging unit about to be added to a variant, with its quantity in base units already resolved (through the unit
 * converter when it is {@code definedAs} another unit).
 *
 * @param code unique per variant, e.g. {@code tray}
 * @param name display name
 * @param qtyInBase base units per packaging unit; positive, 1 for the base unit
 * @param definedAs the size in another unit, if it was given that way
 * @param stockState the variant stock state it draws from
 * @param isBase whether this is the variant's base unit
 * @param canBuy may be bought
 * @param canSell may be sold
 * @param isDefaultSell the unit a till offers first
 */
public record PackagingUnitDraft(
        String code,
        String name,
        BigDecimal qtyInBase,
        @Nullable DefinedQuantity definedAs,
        String stockState,
        boolean isBase,
        boolean canBuy,
        boolean canSell,
        boolean isDefaultSell) {

    public PackagingUnitDraft {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(qtyInBase, "qtyInBase");
        Objects.requireNonNull(stockState, "stockState");
    }

    /** The same unit drawing from another stock state. */
    public PackagingUnitDraft withStockState(String state) {
        return new PackagingUnitDraft(code, name, qtyInBase, definedAs, state, isBase, canBuy, canSell, isDefaultSell);
    }
}
