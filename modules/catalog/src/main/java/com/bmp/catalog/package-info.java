/**
 * Catalog module: the dynamic product model (phase 01, CAT-01..CAT-14). Products are created from templates; their
 * variants carry packaging units defined as multiples of one base unit; transformation definitions describe how stock
 * changes form. Nothing is specific to one business: every product rule is data.
 */
@ApplicationModule(
        displayName = "Catalog",
        allowedDependencies = {"tenant :: api"})
@NullMarked
package com.bmp.catalog;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
