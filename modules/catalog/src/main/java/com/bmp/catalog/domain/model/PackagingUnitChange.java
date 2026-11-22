package com.bmp.catalog.domain.model;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * A partial change of a packaging unit; {@code null} fields stay as they are. A new size comes as {@code qtyInBase}
 * (with {@code definedAs} when it was given in another unit).
 */
public record PackagingUnitChange(
        @Nullable String name,
        @Nullable BigDecimal qtyInBase,
        @Nullable DefinedQuantity definedAs,
        @Nullable String stockState,
        @Nullable Boolean canBuy,
        @Nullable Boolean canSell,
        @Nullable Boolean isDefaultSell,
        @Nullable CatalogStatus status) {

    public boolean resizes() {
        return qtyInBase != null;
    }
}
