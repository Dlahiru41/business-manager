package com.bmp.pricing.domain.model;

/** How a price rule changes the unit price. */
public enum EffectType {
    PERCENT_OFF,
    AMOUNT_OFF,
    FIXED_PRICE,
    MARKUP_PERCENT
}
