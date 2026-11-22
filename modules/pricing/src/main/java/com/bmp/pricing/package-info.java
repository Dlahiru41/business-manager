/**
 * Pricing module: price lists and their versions, prices, price rules, taxes and price resolution (phase 01,
 * PRC-01..PRC-10). It keeps a replica of the catalog variants it prices, fed by catalog events, and implements the
 * catalog's tax category SPI.
 */
@ApplicationModule(
        displayName = "Pricing",
        allowedDependencies = {"tenant :: api", "catalog :: api"})
@NullMarked
package com.bmp.pricing;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
