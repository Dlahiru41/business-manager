package com.bmp.procurement.domain.model;

/** Where a purchase order is (PRO-01). */
public enum OrderStatus {
    DRAFT,
    SENT,
    PARTIALLY_RECEIVED,
    RECEIVED,
    CANCELLED
}
