package com.bmp.catalog.application.query;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A packaging unit as the API shows it; {@code definedAs} carries the unit code, not its id. */
public record PackagingUnitView(
        UUID id,
        UUID variantId,
        String code,
        String name,
        BigDecimal qtyInBase,
        @Nullable DefinedAs definedAs,
        String stockState,
        boolean isBase,
        boolean canBuy,
        boolean canSell,
        boolean isDefaultSell,
        String status,
        List<String> barcodes) {

    public PackagingUnitView {
        barcodes = List.copyOf(barcodes);
    }

    /** The size in another unit. */
    public record DefinedAs(BigDecimal value, String unit) {}
}
