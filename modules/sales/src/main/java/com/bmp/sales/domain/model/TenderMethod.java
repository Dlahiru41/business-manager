package com.bmp.sales.domain.model;

/** How a bill is settled; CREDIT is owed by the customer, not a payment. */
public enum TenderMethod {
    CASH,
    CARD,
    BANK_TRANSFER,
    MOBILE_WALLET,
    CHEQUE,
    CREDIT;

    /** CARD and BANK_TRANSFER carry a reference (SAL-06). */
    public boolean needsReference() {
        return this == CARD || this == BANK_TRANSFER;
    }
}
