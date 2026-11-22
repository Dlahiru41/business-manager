package com.bmp.catalog.api.dto;

import com.bmp.shared.id.PackagingUnitId;
import com.bmp.shared.id.VariantId;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * A packaging unit for other modules (read-only).
 *
 * @param id the packaging unit
 * @param variantId its variant
 * @param code unique per variant
 * @param name display name
 * @param qtyInBase base units per packaging unit
 * @param stockState the variant stock state it draws from
 * @param isBase whether it is the variant's base unit
 * @param canBuy may be bought
 * @param canSell may be sold
 * @param isDefaultSell the unit a till offers first
 * @param active whether it is active
 * @param barcodes barcodes mapping to it
 */
public record PackagingUnitInfo(
        PackagingUnitId id,
        VariantId variantId,
        String code,
        String name,
        BigDecimal qtyInBase,
        String stockState,
        boolean isBase,
        boolean canBuy,
        boolean canSell,
        boolean isDefaultSell,
        boolean active,
        List<String> barcodes) {

    public PackagingUnitInfo {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(qtyInBase, "qtyInBase");
        barcodes = List.copyOf(barcodes);
    }
}
