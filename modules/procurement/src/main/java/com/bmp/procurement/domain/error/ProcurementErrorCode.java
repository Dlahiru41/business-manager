package com.bmp.procurement.domain.error;

import static com.bmp.shared.error.ErrorCategory.CONFLICT;
import static com.bmp.shared.error.ErrorCategory.FORBIDDEN;
import static com.bmp.shared.error.ErrorCategory.NOT_FOUND;
import static com.bmp.shared.error.ErrorCategory.VALIDATION;

import com.bmp.shared.error.ErrorCategory;
import com.bmp.shared.error.ErrorCode;

/** Error codes of the procurement module. Codes are never renamed once released. */
public enum ProcurementErrorCode implements ErrorCode {
    PURCHASE_ORDER_NOT_FOUND(NOT_FOUND),
    GOODS_RECEIPT_NOT_FOUND(NOT_FOUND),
    SUPPLIER_INVOICE_NOT_FOUND(NOT_FOUND),
    UNKNOWN_SUPPLIER(VALIDATION),
    UNKNOWN_LOCATION(VALIDATION),
    UNKNOWN_VARIANT(VALIDATION),
    UNKNOWN_PACKAGING_UNIT(VALIDATION),
    UNIT_NOT_PURCHASABLE(VALIDATION),
    PURCHASE_ORDER_MISMATCH(VALIDATION),
    MIXED_LINE_SIGNS(VALIDATION),
    CORRECTION_TARGET_INVALID(VALIDATION),
    EXPIRY_REQUIRED(VALIDATION),
    UNKNOWN_RECEIPT(VALIDATION),
    RECEIPT_NOT_POSTED(VALIDATION),
    SUPPLIER_MISMATCH(VALIDATION),
    PURCHASE_ORDER_STATE_INVALID(CONFLICT),
    GOODS_RECEIPT_STATE_INVALID(CONFLICT),
    SUPPLIER_INVOICE_REF_TAKEN(CONFLICT),
    RECEIPT_ALREADY_INVOICED(CONFLICT),
    INVOICE_STATE_INVALID(CONFLICT),
    CORRECTION_PERMISSION_REQUIRED(FORBIDDEN);

    private final ErrorCategory category;

    ProcurementErrorCode(ErrorCategory category) {
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
        return "procurement";
    }
}
