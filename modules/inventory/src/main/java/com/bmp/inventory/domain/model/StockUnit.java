package com.bmp.inventory.domain.model;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A packaging unit of a stock item, as the catalog last published it.
 *
 * @param qtyInBase base units in one of this unit (INV-05)
 * @param stockState the state stock of this unit is kept in
 */
public record StockUnit(
        UUID id,
        String code,
        BigDecimal qtyInBase,
        String stockState,
        boolean isBase,
        boolean canBuy,
        boolean canSell,
        boolean active) {}
