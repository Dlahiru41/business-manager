/**
 * Procurement module: purchase orders, goods receipts and supplier invoices (phase 02, PRO-01..PRO-07). Posting a
 * receipt publishes {@code GoodsReceived}, which inventory books; reorder suggestions come from inventory through the
 * {@code api.spi.ReorderSuggestionProvider} SPI, so procurement never depends on inventory.
 */
@ApplicationModule(
        displayName = "Procurement",
        allowedDependencies = {"tenant :: api", "catalog :: api", "party :: api"})
@NullMarked
package com.bmp.procurement;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
