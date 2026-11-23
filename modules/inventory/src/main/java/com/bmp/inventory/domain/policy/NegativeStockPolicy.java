package com.bmp.inventory.domain.policy;

/**
 * Rule INV-08, the tenant setting {@code inventory.negativeStockPolicy}: whether adjustments and transformations may
 * take a balance below zero. Sales never consult it (INV-07).
 */
public enum NegativeStockPolicy {
    ALLOW_AND_FLAG,
    BLOCK
}
