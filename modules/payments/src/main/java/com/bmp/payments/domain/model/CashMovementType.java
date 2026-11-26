package com.bmp.payments.domain.model;

/** Why cash moved in or out of a drawer (PAY-04). */
public enum CashMovementType {
    FLOAT(true),
    SALE(true),
    REFUND(false),
    PAID_OUT(false),
    PAID_IN(true),
    DROP(false);

    private final boolean into;

    CashMovementType(boolean into) {
        this.into = into;
    }

    /** Whether the cash goes into the drawer. */
    public boolean into() {
        return into;
    }

    /** What a person may record by hand: the rest follows from sales, refunds and the opening float. */
    public boolean manual() {
        return this == PAID_OUT || this == PAID_IN || this == DROP;
    }
}
