package com.bmp.finance.domain.model;

/** What a journal was posted for (posting-rules.md §2, plus manual journals and their reversals). */
public enum SourceType {
    SALE,
    BILL_PAYMENT,
    COLLECTION,
    STOCK_ISSUE,
    SALE_RETURN,
    PAYMENT_REFUND,
    SALE_VOID,
    GOODS_RECEIPT,
    STOCK_RECEIPT,
    SUPPLIER_INVOICE,
    SUPPLIER_PAYMENT,
    STOCK_ADJUSTMENT,
    STOCK_COUNT,
    STOCK_TRANSFORMATION,
    EXPENSE,
    DRAWER_RECONCILIATION,
    CASH_DROP,
    YEAR_END_CLOSE,
    MANUAL,
    REVERSAL;

    /** Journals a person enters; every other type is posted automatically from an event or a finance command. */
    public boolean isManual() {
        return this == MANUAL || this == REVERSAL;
    }
}
