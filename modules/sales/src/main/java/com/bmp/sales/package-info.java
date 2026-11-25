/**
 * Sales module: registers, shifts, bills (online and offline), discounts, tenders, credit sales, voids, returns and
 * credit notes (phase 03, SAL-01..SAL-15). It keeps replicas of the sellable variants (catalog) and the customers
 * (party), prices online bills through {@code pricing::api} and checks approvals through {@code iam::api}. Payments and
 * inventory react to its events; sales depends on neither, and credit exposure comes through the
 * {@code api.spi.CreditExposureProvider} SPI.
 */
@ApplicationModule(
        displayName = "Sales",
        allowedDependencies = {"tenant :: api", "iam :: api", "catalog :: api", "party :: api", "pricing :: api"})
@NullMarked
package com.bmp.sales;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
