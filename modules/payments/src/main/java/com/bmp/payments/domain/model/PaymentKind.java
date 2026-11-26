package com.bmp.payments.domain.model;

/** Where a payment comes from: a bill's tender or a collection on a customer's account (PAY-01, PAY-02). */
public enum PaymentKind {
    BILL,
    ACCOUNT
}
