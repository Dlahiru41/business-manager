package com.bmp.pricing.domain.error;

import static com.bmp.shared.error.ErrorCategory.CONFLICT;
import static com.bmp.shared.error.ErrorCategory.FORBIDDEN;
import static com.bmp.shared.error.ErrorCategory.NOT_FOUND;
import static com.bmp.shared.error.ErrorCategory.VALIDATION;

import com.bmp.shared.error.ErrorCategory;
import com.bmp.shared.error.ErrorCode;

/** Error codes of the pricing module. Codes are never renamed once released. */
public enum PricingErrorCode implements ErrorCode {
    PRICE_LIST_NOT_FOUND(NOT_FOUND),
    PRICE_LIST_VERSION_NOT_FOUND(NOT_FOUND),
    PRICE_RULE_NOT_FOUND(NOT_FOUND),
    TAX_CATEGORY_NOT_FOUND(NOT_FOUND),
    TAX_RATE_NOT_FOUND(NOT_FOUND),

    PRICE_LIST_CODE_TAKEN(CONFLICT),
    PRICE_RULE_CODE_TAKEN(CONFLICT),
    TAX_CATEGORY_CODE_TAKEN(CONFLICT),
    /** PRC-02: validity windows of the same list, unit and minimum quantity never overlap. */
    PRICE_OVERLAP(CONFLICT),
    TAX_RATE_OVERLAP(CONFLICT),

    /** PRC-01: a list is for a customer group or a customer, not both. */
    INVALID_PRICE_LIST_SCOPE(VALIDATION),
    INVALID_VALIDITY(VALIDATION),
    INVALID_RULE_EFFECT(VALIDATION),
    DUPLICATE_PRICE_ROWS(VALIDATION),
    UNKNOWN_VARIANT(VALIDATION),
    UNKNOWN_PACKAGING_UNIT(VALIDATION),
    UNKNOWN_TAX_CATEGORY(VALIDATION),

    /** PRC-08: the tenant lets only owners change prices. */
    PRICE_CHANGE_RESTRICTED(FORBIDDEN);

    private final ErrorCategory category;

    PricingErrorCode(ErrorCategory category) {
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
        return "pricing";
    }
}
