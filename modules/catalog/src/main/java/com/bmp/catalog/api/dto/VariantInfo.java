package com.bmp.catalog.api.dto;

import com.bmp.shared.id.ProductId;
import com.bmp.shared.id.VariantId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A variant for other modules (read-only): what sync and later modules need to sell, buy and count it.
 *
 * @param id the variant
 * @param productId its product
 * @param sku unique per tenant
 * @param name display name
 * @param baseUnit the code of its base unit
 * @param stockStates the states its stock is kept in
 * @param sellMode PREPACKED or BREAK_ON_SALE
 * @param active whether it is active
 * @param sellable CAT-08: active with an active unit that can be sold
 * @param purchasable CAT-08: active with an active unit that can be bought
 * @param taxCategoryId its product's tax category, if any
 * @param catalogRevision the catalog version of its last change
 * @param packagingUnits its packaging units, base unit first
 */
public record VariantInfo(
        VariantId id,
        ProductId productId,
        String sku,
        String name,
        String baseUnit,
        List<String> stockStates,
        String sellMode,
        boolean active,
        boolean sellable,
        boolean purchasable,
        @Nullable UUID taxCategoryId,
        long catalogRevision,
        List<PackagingUnitInfo> packagingUnits) {

    public VariantInfo {
        Objects.requireNonNull(id, "id");
        stockStates = List.copyOf(stockStates);
        packagingUnits = List.copyOf(packagingUnits);
    }
}
