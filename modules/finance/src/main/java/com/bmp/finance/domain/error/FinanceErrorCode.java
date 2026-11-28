package com.bmp.finance.domain.error;

import static com.bmp.shared.error.ErrorCategory.CONFLICT;
import static com.bmp.shared.error.ErrorCategory.INTERNAL;
import static com.bmp.shared.error.ErrorCategory.NOT_FOUND;
import static com.bmp.shared.error.ErrorCategory.VALIDATION;

import com.bmp.shared.error.ErrorCategory;
import com.bmp.shared.error.ErrorCode;

/** Error codes of the finance module (04-error-handling.md §1); listed per operation in {@code finance.yaml}. */
public enum FinanceErrorCode implements ErrorCode {
    ACCOUNT_NOT_FOUND(NOT_FOUND),
    JOURNAL_NOT_FOUND(NOT_FOUND),
    EXPENSE_CATEGORY_NOT_FOUND(NOT_FOUND),
    EXPENSE_NOT_FOUND(NOT_FOUND),
    SUPPLIER_PAYMENT_NOT_FOUND(NOT_FOUND),
    PARTY_NOT_KNOWN(NOT_FOUND),
    ACCOUNT_CODE_TAKEN(CONFLICT),
    SYSTEM_ACCOUNT_LOCKED(CONFLICT),
    EXPENSE_CATEGORY_CODE_TAKEN(CONFLICT),
    JOURNAL_ALREADY_REVERSED(CONFLICT),
    PERIOD_NOT_ENDED(CONFLICT),
    PERIOD_ALREADY_CLOSED(CONFLICT),
    PERIOD_CLOSE_IN_PROGRESS(CONFLICT),
    EARLIER_PERIOD_OPEN(CONFLICT),
    FAILED_EVENTS_PENDING(CONFLICT),
    POS_CLEARING_NOT_ZERO(CONFLICT),
    DRAWERS_NOT_RECONCILED(CONFLICT),
    UNKNOWN_PARENT_ACCOUNT(VALIDATION),
    UNKNOWN_ACCOUNT(VALIDATION),
    ACCOUNT_INACTIVE(VALIDATION),
    ACCOUNT_NOT_EXPENSE(VALIDATION),
    JOURNAL_UNBALANCED(VALIDATION),
    UNKNOWN_EXPENSE_CATEGORY(VALIDATION),
    DRAWER_SESSION_REQUIRED(VALIDATION),
    SUPPLIER_REQUIRED(VALIDATION),
    UNKNOWN_SUPPLIER(VALIDATION),
    UNKNOWN_OPEN_ITEM(VALIDATION),
    ALLOCATION_EXCEEDS_OPEN(VALIDATION),
    ALLOCATION_EXCEEDS_PAYMENT(VALIDATION),
    INVALID_PERIOD(VALIDATION),
    /** Rule FIN-03: Σ debit ≠ Σ credit in an automatic journal; a bug, never a client error. */
    LEDGER_IMBALANCE(INTERNAL),
    /** An event value finance has no posting for (a tender method or source type it does not know). */
    POSTING_RULE_MISSING(INTERNAL);

    private final ErrorCategory category;

    FinanceErrorCode(ErrorCategory category) {
        this.category = category;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public ErrorCategory category() {
        return category;
    }

    @Override
    public String module() {
        return "finance";
    }
}
