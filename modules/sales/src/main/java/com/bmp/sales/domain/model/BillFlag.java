package com.bmp.sales.domain.model;

/** Why an offline bill was accepted although it breaks an online rule (SAL-03..SAL-07, SAL-12, SYN-08). */
public enum BillFlag {
    PRICE_MISMATCH,
    TOTALS_MISMATCH,
    DISCOUNT_LIMIT_EXCEEDED,
    CREDIT_LIMIT_EXCEEDED,
    LATE_SYNC,
    OFFLINE_SHIFT,
    TENDER_MISMATCH
}
