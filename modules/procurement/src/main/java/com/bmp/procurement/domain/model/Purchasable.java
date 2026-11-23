package com.bmp.procurement.domain.model;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A packaging unit a variant can be bought in (PRO-01), as the catalog says.
 *
 * @param qtyInBase base units in one of it
 * @param stockState the state stock of it is kept in
 * @param trackExpiry whether its receipts need an expiry date
 */
public record Purchasable(
        UUID variantId, UUID packagingUnitId, BigDecimal qtyInBase, String stockState, boolean trackExpiry) {}
