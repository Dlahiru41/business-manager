package com.bmp.payments.domain.model;

/** How money was taken (CREDIT is not a payment: it is owed, data-model.md). */
public enum PaymentMethod {
    CASH,
    CARD,
    BANK_TRANSFER,
    MOBILE_WALLET,
    CHEQUE;

    /** CARD and BANK_TRANSFER carry a reference (SAL-06, PAY-02). */
    public boolean needsReference() {
        return this == CARD || this == BANK_TRANSFER;
    }

    /** The method of a tender, or empty for CREDIT and anything else that is not money taken. */
    public static java.util.Optional<PaymentMethod> ofTender(String method) {
        for (PaymentMethod m : values()) {
            if (m.name().equals(method)) {
                return java.util.Optional.of(m);
            }
        }
        return java.util.Optional.empty();
    }
}
