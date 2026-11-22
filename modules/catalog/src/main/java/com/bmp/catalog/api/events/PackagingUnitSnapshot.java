package com.bmp.catalog.api.events;

import com.bmp.shared.id.PackagingUnitId;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * A packaging unit as carried by variant events, so consumers keep replicas without calling back (CAT-13).
 *
 * @param id the packaging unit
 * @param code unique per variant, e.g. {@code tray}
 * @param name display name
 * @param qtyInBase base units per packaging unit
 * @param stockState the variant stock state it draws from
 * @param isBase whether it is the variant's base unit
 * @param canBuy may be bought
 * @param canSell may be sold
 * @param isDefaultSell the unit a till offers first
 * @param status ACTIVE or INACTIVE
 * @param barcodes barcodes mapping to this unit
 */
public record PackagingUnitSnapshot(
        PackagingUnitId id,
        String code,
        String name,
        BigDecimal qtyInBase,
        String stockState,
        boolean isBase,
        boolean canBuy,
        boolean canSell,
        boolean isDefaultSell,
        String status,
        List<String> barcodes) {

    public PackagingUnitSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(qtyInBase, "qtyInBase");
        barcodes = List.copyOf(barcodes);
    }
}
