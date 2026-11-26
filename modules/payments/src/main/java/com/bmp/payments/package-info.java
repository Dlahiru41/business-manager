/**
 * Payments module: money taken for bills and on customers' accounts, refunds, cash drawers from float to
 * reconciliation, and daily totals by method (phase 03, PAY-01..PAY-06). It reacts to the sales events; sales never
 * calls it.
 */
@ApplicationModule(
        displayName = "Payments",
        allowedDependencies = {"tenant :: api", "sales :: api", "party :: api"})
@NullMarked
package com.bmp.payments;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
