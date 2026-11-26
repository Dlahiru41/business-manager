package com.bmp.sales.domain.port;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The replica of sellable variants, fed by catalog events (version-guarded). */
public interface SaleItemStore {

    /** A packaging unit as sales needs it. */
    record Unit(
            UUID id,
            String code,
            String name,
            BigDecimal qtyInBase,
            String stockState,
            boolean isBase,
            boolean canSell,
            boolean active) {}

    /** A variant as sales needs it. */
    record SaleItem(
            UUID variantId,
            UUID productId,
            String sku,
            String name,
            String baseUnit,
            boolean weighable,
            List<Unit> units,
            boolean active) {

        public SaleItem {
            units = List.copyOf(units);
        }

        public Optional<Unit> unit(UUID unitId) {
            return units.stream().filter(u -> u.id().equals(unitId)).findFirst();
        }
    }

    Optional<SaleItem> find(UUID variantId);

    /** Stores the variant unless a newer version is already there. */
    void upsert(SaleItem item, long version);

    /** Marks the variant inactive unless a newer version is already there. */
    void deactivate(UUID variantId, long version);
}
