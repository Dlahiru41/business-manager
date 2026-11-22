package com.bmp.catalog.domain.model;

/**
 * How selling a packaging unit takes stock: {@code PREPACKED} units are kept in stock as such (filled bottles, own
 * packets in their own stock state); {@code BREAK_ON_SALE} units take their base quantity from bulk stock (750 ml from
 * the open can).
 */
public enum SellMode {
    PREPACKED,
    BREAK_ON_SALE
}
