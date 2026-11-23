package com.bmp.party.domain.error;

import static com.bmp.shared.error.ErrorCategory.CONFLICT;
import static com.bmp.shared.error.ErrorCategory.FORBIDDEN;
import static com.bmp.shared.error.ErrorCategory.NOT_FOUND;
import static com.bmp.shared.error.ErrorCategory.VALIDATION;

import com.bmp.shared.error.ErrorCategory;
import com.bmp.shared.error.ErrorCode;

/** Error codes of the party module. Codes are never renamed once released. */
public enum PartyErrorCode implements ErrorCode {
    PARTY_NOT_FOUND(NOT_FOUND),
    CUSTOMER_GROUP_NOT_FOUND(NOT_FOUND),
    INVALID_PHONE(VALIDATION),
    UNKNOWN_CUSTOMER_GROUP(VALIDATION),
    DUPLICATE_PARTY_PHONE(CONFLICT),
    CUSTOMER_GROUP_CODE_TAKEN(CONFLICT),
    PARTY_ANONYMISED(CONFLICT),
    PARTY_PERMISSION_REQUIRED(FORBIDDEN);

    private final ErrorCategory category;

    PartyErrorCode(ErrorCategory category) {
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
        return "party";
    }
}
