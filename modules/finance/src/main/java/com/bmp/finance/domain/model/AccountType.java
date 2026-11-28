package com.bmp.finance.domain.model;

/** The five account types of double entry. */
public enum AccountType {
    ASSET,
    LIABILITY,
    EQUITY,
    INCOME,
    EXPENSE;

    /** Assets and expenses grow on the debit side; the others on the credit side. */
    public boolean debitNormal() {
        return this == ASSET || this == EXPENSE;
    }

    /** Income and expenses are closed to retained earnings at year end. */
    public boolean isProfitAndLoss() {
        return this == INCOME || this == EXPENSE;
    }
}
