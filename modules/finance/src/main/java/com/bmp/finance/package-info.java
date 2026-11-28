/**
 * Finance module: the double-entry ledger fed by every other module's events (posting-rules.md), expenses, receivables
 * and payables with their open items, supplier payments, cash drops and fiscal periods (phase 04, FIN-01..FIN-11). It
 * implements sales' {@code CreditExposureProvider} SPI; no upstream module calls it.
 */
@ApplicationModule(
        displayName = "Finance",
        allowedDependencies = {
            "tenant :: api",
            "party :: api",
            "catalog :: api",
            "procurement :: api",
            "inventory :: api",
            "sales :: api",
            "payments :: api"
        })
@NullMarked
package com.bmp.finance;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
