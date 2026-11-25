package com.bmp.sales.domain.error;

import static com.bmp.shared.error.ErrorCategory.CONFLICT;
import static com.bmp.shared.error.ErrorCategory.FORBIDDEN;
import static com.bmp.shared.error.ErrorCategory.NOT_FOUND;
import static com.bmp.shared.error.ErrorCategory.VALIDATION;

import com.bmp.shared.error.ErrorCategory;
import com.bmp.shared.error.ErrorCode;

/** Error codes of the sales module. Codes are never renamed once released. */
public enum SalesErrorCode implements ErrorCode {
    BILL_NOT_FOUND(NOT_FOUND),
    REGISTER_NOT_FOUND(NOT_FOUND),
    SHIFT_NOT_FOUND(NOT_FOUND),
    CREDIT_NOTE_NOT_FOUND(NOT_FOUND),
    UNKNOWN_REGISTER(VALIDATION),
    UNKNOWN_LOCATION(VALIDATION),
    UNKNOWN_VARIANT(VALIDATION),
    UNKNOWN_PACKAGING_UNIT(VALIDATION),
    UNIT_NOT_SELLABLE(VALIDATION),
    WEIGHED_QTY_INVALID(VALIDATION),
    UNKNOWN_CUSTOMER(VALIDATION),
    CUSTOMER_REQUIRED(VALIDATION),
    UNKNOWN_BILL_LINE(VALIDATION),
    PRICE_NOT_FOUND(VALIDATION),
    PRICE_MISMATCH(VALIDATION),
    TOTALS_MISMATCH(VALIDATION),
    TENDER_MISMATCH(VALIDATION),
    TENDER_INVALID(VALIDATION),
    CARD_DATA_NOT_ALLOWED(VALIDATION),
    CREDIT_NOT_ALLOWED(VALIDATION),
    REFUND_TENDER_MISMATCH(VALIDATION),
    APPROVAL_REQUIRED(VALIDATION),
    APPROVER_NOT_ALLOWED(FORBIDDEN),
    PRICE_OVERRIDE_NOT_ALLOWED(FORBIDDEN),
    DISCOUNT_LIMIT_EXCEEDED(FORBIDDEN),
    BILL_NUMBER_CONFLICT(CONFLICT),
    BILL_STATE_INVALID(CONFLICT),
    CREDIT_LIMIT_EXCEEDED(CONFLICT),
    SHIFT_NOT_OPEN(CONFLICT),
    SHIFT_ALREADY_OPEN(CONFLICT),
    SHIFT_STATE_INVALID(CONFLICT),
    VOID_WINDOW_PASSED(CONFLICT),
    RETURN_WINDOW_PASSED(CONFLICT),
    RETURN_QTY_EXCEEDED(CONFLICT),
    REGISTER_CODE_TAKEN(CONFLICT),
    BILL_PREFIX_TAKEN(CONFLICT);

    private final ErrorCategory category;

    SalesErrorCode(ErrorCategory category) {
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
        return "sales";
    }
}
