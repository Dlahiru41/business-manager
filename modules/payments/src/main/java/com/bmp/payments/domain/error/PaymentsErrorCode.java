package com.bmp.payments.domain.error;

import static com.bmp.shared.error.ErrorCategory.CONFLICT;
import static com.bmp.shared.error.ErrorCategory.FORBIDDEN;
import static com.bmp.shared.error.ErrorCategory.NOT_FOUND;
import static com.bmp.shared.error.ErrorCategory.VALIDATION;

import com.bmp.shared.error.ErrorCategory;
import com.bmp.shared.error.ErrorCode;

/** Error codes of the payments module. Codes are never renamed once released. */
public enum PaymentsErrorCode implements ErrorCode {
    PAYMENT_NOT_FOUND(NOT_FOUND),
    DRAWER_SESSION_NOT_FOUND(NOT_FOUND),
    UNKNOWN_CUSTOMER(VALIDATION),
    UNKNOWN_REGISTER(VALIDATION),
    CARD_DATA_NOT_ALLOWED(VALIDATION),
    TENDERED_TOO_LOW(VALIDATION),
    REFERENCE_REQUIRED(VALIDATION),
    DENOMINATION_UNKNOWN(VALIDATION),
    DRAWER_NOT_YOURS(FORBIDDEN),
    DRAWER_OF_ANOTHER_REGISTER(FORBIDDEN),
    REFUND_EXCEEDS_PAYMENT(CONFLICT),
    DRAWER_NOT_OPEN(CONFLICT),
    DRAWER_ALREADY_OPEN(CONFLICT),
    DRAWER_STATE_INVALID(CONFLICT);

    private final ErrorCategory category;

    PaymentsErrorCode(ErrorCategory category) {
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
        return "payments";
    }
}
