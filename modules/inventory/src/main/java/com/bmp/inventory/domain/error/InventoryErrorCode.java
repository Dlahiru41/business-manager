package com.bmp.inventory.domain.error;

import static com.bmp.shared.error.ErrorCategory.CONFLICT;
import static com.bmp.shared.error.ErrorCategory.NOT_FOUND;
import static com.bmp.shared.error.ErrorCategory.VALIDATION;

import com.bmp.shared.error.ErrorCategory;
import com.bmp.shared.error.ErrorCode;

/** Error codes of the inventory module. Codes are never renamed once released. */
public enum InventoryErrorCode implements ErrorCode {
    STOCK_ITEM_NOT_FOUND(NOT_FOUND),
    TRANSFORMATION_RUN_NOT_FOUND(NOT_FOUND),
    STOCK_COUNT_NOT_FOUND(NOT_FOUND),
    UNKNOWN_VARIANT(VALIDATION),
    UNKNOWN_LOCATION(VALIDATION),
    UNKNOWN_PACKAGING_UNIT(VALIDATION),
    INVALID_STOCK_STATE(VALIDATION),
    UNKNOWN_BATCH(VALIDATION),
    EXPIRY_REQUIRED(VALIDATION),
    BATCH_REQUIRED(VALIDATION),
    UNKNOWN_TRANSFORMATION_DEFINITION(VALIDATION),
    INVALID_OUTPUTS(VALIDATION),
    TRANSFER_SAME_LOCATION(VALIDATION),
    STOCK_ITEM_INACTIVE(CONFLICT),
    INSUFFICIENT_STOCK(CONFLICT),
    TRANSFORMATION_DEFINITION_INACTIVE(CONFLICT),
    OPENING_BALANCE_EXISTS(CONFLICT),
    COUNT_STATE_INVALID(CONFLICT),
    COUNT_ALREADY_POSTED(CONFLICT);

    private final ErrorCategory category;

    InventoryErrorCode(ErrorCategory category) {
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
        return "inventory";
    }
}
