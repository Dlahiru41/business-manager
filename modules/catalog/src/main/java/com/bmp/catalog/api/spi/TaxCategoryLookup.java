package com.bmp.catalog.api.spi;

import java.util.UUID;

/**
 * Lets the catalog check the tax category of a product without depending on the pricing module, which owns tax
 * categories (01 §2: dependency inversion). Pricing implements it; without an implementation every id is accepted,
 * since no tax category can exist without pricing.
 */
public interface TaxCategoryLookup {

    /** Whether the tax category exists in the current tenant. */
    boolean exists(UUID taxCategoryId);
}
