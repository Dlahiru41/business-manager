package com.bmp.inventory.application;

import com.bmp.inventory.application.query.StockReadModel;
import com.bmp.procurement.api.spi.ReorderSuggestion;
import com.bmp.procurement.api.spi.ReorderSuggestionProvider;
import com.bmp.shared.id.LocationId;
import com.bmp.shared.id.PartyId;
import com.bmp.shared.id.VariantId;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rule PRO-06: inventory's reorder list for procurement's "orders from the reorder list", through procurement's SPI
 * (procurement never imports inventory). The cost is the last received cost per base unit.
 */
@Component
class InventoryReorderSuggestions implements ReorderSuggestionProvider {

    private final StockReadModel model;

    InventoryReorderSuggestions(StockReadModel model) {
        this.model = model;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReorderSuggestion> suggestions(@Nullable LocationId location) {
        return model.reorderList(location == null ? null : location.value()).stream()
                .map(r -> new ReorderSuggestion(
                        VariantId.of(r.variantId()),
                        LocationId.of(r.locationId()),
                        r.reorderQty(),
                        r.preferredSupplierId() == null ? null : PartyId.of(r.preferredSupplierId()),
                        r.lastUnitCostBase()))
                .toList();
    }
}
