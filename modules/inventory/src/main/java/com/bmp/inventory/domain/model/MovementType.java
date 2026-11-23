package com.bmp.inventory.domain.model;

/** Why stock moved (data-model.md, stock_movement.type). */
public enum MovementType {
    OPENING,
    RECEIPT,
    SALE,
    SALE_RETURN,
    SALE_VOID,
    TRANSFORM_IN,
    TRANSFORM_OUT,
    WASTAGE,
    COUNT_ADJ,
    CORRECTION,
    TRANSFER_IN,
    TRANSFER_OUT
}
