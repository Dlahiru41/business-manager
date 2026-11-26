package com.bmp.inventory.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** What other modules may ask inventory about the current tenant's stock. */
public interface InventoryQueries {

    /** Stock a till may sell at a location, by variant and stock state, batches added up (SYN-02). */
    List<Available> available(UUID locationId);

    record Available(UUID variantId, String stockState, BigDecimal available) {}
}
