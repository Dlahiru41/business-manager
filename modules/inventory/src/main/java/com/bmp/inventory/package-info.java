/**
 * Inventory module: stock as an append-only ledger in base units with live balances, costing (moving average or
 * FIFO), transformations, wastage, transfers, counts and reorder alerts (phase 02, INV-01..INV-17). It keeps replicas
 * of the catalog variants and definitions and of the tenant's locations, fed by their events.
 */
@ApplicationModule(
        displayName = "Inventory",
        allowedDependencies = {"tenant :: api", "catalog :: api", "procurement :: api", "sales :: api"})
@NullMarked
package com.bmp.inventory;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
