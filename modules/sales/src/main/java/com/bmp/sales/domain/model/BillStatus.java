package com.bmp.sales.domain.model;

/** A bill's lifecycle: DRAFT → COMPLETED → (PARTIALLY_RETURNED | RETURNED | VOIDED). */
public enum BillStatus {
    DRAFT,
    COMPLETED,
    VOIDED,
    PARTIALLY_RETURNED,
    RETURNED
}
