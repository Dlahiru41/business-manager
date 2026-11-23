package com.bmp.procurement.api.spi;

import com.bmp.shared.id.LocationId;
import com.bmp.shared.id.PartyId;
import com.bmp.shared.id.VariantId;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * Something to reorder (PRO-06): a variant at or below its reorder level in a location.
 *
 * @param reorderQtyBase how much to order, in base units
 * @param lastUnitCostBase the last known cost of one base unit, if any
 */
public record ReorderSuggestion(
        VariantId variantId,
        LocationId locationId,
        BigDecimal reorderQtyBase,
        @Nullable PartyId preferredSupplierId,
        @Nullable BigDecimal lastUnitCostBase) {}
