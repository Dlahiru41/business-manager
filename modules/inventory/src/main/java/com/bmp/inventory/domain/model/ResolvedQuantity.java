package com.bmp.inventory.domain.model;

import java.math.BigDecimal;

/** A quantity in base units and the stock state it is kept in (INV-05). */
public record ResolvedQuantity(BigDecimal qtyBase, String stockState) {}
