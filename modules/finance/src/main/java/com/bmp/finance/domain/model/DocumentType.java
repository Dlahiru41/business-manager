package com.bmp.finance.domain.model;

/** The document behind an open item or a settlement (FIN-07). */
public enum DocumentType {
    BILL,
    COLLECTION,
    RETURN,
    VOID,
    REFUND,
    SUPPLIER_INVOICE,
    SUPPLIER_PAYMENT,
    EXPENSE,
    SUPPLIER_RETURN
}
