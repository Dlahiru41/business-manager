package com.bmp.catalog.application.command;

import java.math.BigDecimal;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A packaging unit as a request describes it: its size either in base units ({@code qtyInBase}) or in another unit
 * ({@code definedAs}); flags and stock state default from the product template.
 *
 * @param code unique per variant
 * @param name display name
 * @param qtyInBase base units per packaging unit
 * @param definedAs the size in another unit (the 18 kg can)
 * @param stockState the stock state it draws from, or the template default
 * @param canBuy may be bought (default: no, the template decides for the base unit)
 * @param canSell may be sold (default: no, the template decides for the base unit)
 * @param isDefaultSell the unit a till offers first
 */
public record PackagingUnitSpec(
        String code,
        String name,
        @Nullable BigDecimal qtyInBase,
        @Nullable DefinedAsSpec definedAs,
        @Nullable String stockState,
        @Nullable Boolean canBuy,
        @Nullable Boolean canSell,
        @Nullable Boolean isDefaultSell) {

    public PackagingUnitSpec {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
    }

    /**
     * A size given in a unit other than the base unit.
     *
     * @param value the size
     * @param unit the unit code, e.g. {@code kg}
     */
    public record DefinedAsSpec(BigDecimal value, String unit) {}
}
