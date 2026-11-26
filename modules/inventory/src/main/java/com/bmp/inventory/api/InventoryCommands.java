package com.bmp.inventory.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Stock commands for modules that replay what a till did offline (sync, SYN-04), in the caller's transaction or its own
 * when there is none. A wastage is not idempotent by itself: sync records each uploaded record once in the same
 * transaction.
 */
public interface InventoryCommands {

    /** Rule INV-11: stock lost at a location, at current cost. */
    void recordWastage(Wastage wastage);

    /**
     * Stock lost at a location.
     *
     * @param reason BREAKAGE, SPOILAGE, SPILLAGE, EXPIRED, THEFT, DAMAGED or OTHER (with a note)
     */
    record Wastage(UUID locationId, String reason, @Nullable String note, Instant occurredAt, List<WastageLine> lines) {

        public Wastage {
            lines = List.copyOf(lines);
        }
    }

    /** A quantity in a packaging unit (the base unit when none) and stock state (the unit's when none). */
    record WastageLine(
            UUID variantId,
            @Nullable UUID packagingUnitId,
            @Nullable String stockState,
            BigDecimal qty) {}
}
