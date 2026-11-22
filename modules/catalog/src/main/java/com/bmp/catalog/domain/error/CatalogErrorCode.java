package com.bmp.catalog.domain.error;

import static com.bmp.shared.error.ErrorCategory.CONFLICT;
import static com.bmp.shared.error.ErrorCategory.NOT_FOUND;
import static com.bmp.shared.error.ErrorCategory.VALIDATION;

import com.bmp.shared.error.ErrorCategory;
import com.bmp.shared.error.ErrorCode;

/** Error codes of the catalog module. Codes are never renamed once released. */
public enum CatalogErrorCode implements ErrorCode {
    PRODUCT_NOT_FOUND(NOT_FOUND),
    VARIANT_NOT_FOUND(NOT_FOUND),
    PACKAGING_UNIT_NOT_FOUND(NOT_FOUND),
    CATEGORY_NOT_FOUND(NOT_FOUND),
    ATTRIBUTE_NOT_FOUND(NOT_FOUND),
    TRANSFORMATION_DEFINITION_NOT_FOUND(NOT_FOUND),
    BARCODE_NOT_FOUND(NOT_FOUND),

    PRODUCT_CODE_TAKEN(CONFLICT),
    /** CAT-06: SKUs are unique per tenant. */
    SKU_ALREADY_EXISTS(CONFLICT),
    /** CAT-12: a barcode maps to exactly one packaging unit of the tenant. */
    BARCODE_ALREADY_EXISTS(CONFLICT),
    UNIT_CODE_TAKEN(CONFLICT),
    /** CAT-04: packaging unit codes are unique per variant. */
    PACKAGING_UNIT_CODE_TAKEN(CONFLICT),
    CATEGORY_NAME_TAKEN(CONFLICT),
    ATTRIBUTE_CODE_TAKEN(CONFLICT),
    ATTRIBUTE_VALUE_CODE_TAKEN(CONFLICT),
    DEFINITION_CODE_TAKEN(CONFLICT),
    /** Attribute types are fixed once a product has variants; values may still be added. */
    ATTRIBUTE_TYPES_LOCKED(CONFLICT),
    VARIANT_COMBINATION_EXISTS(CONFLICT),
    /** CAT-02: the base packaging unit always exists with qtyInBase 1. */
    BASE_UNIT_IMMUTABLE(CONFLICT),
    PRODUCT_INACTIVE(CONFLICT),
    /** CAT-10: a published version is immutable; publish only drafts. */
    DEFINITION_ALREADY_PUBLISHED(CONFLICT),
    DEFINITION_RETIRED(CONFLICT),

    /** CAT-03: no conversion between the units, neither by SI factors nor a product conversion. */
    UNIT_NOT_CONVERTIBLE(VALIDATION),
    /** CAT-07: a packaging unit draws from one of its variant's stock states. */
    INVALID_STOCK_STATE(VALIDATION),
    /** CAT-04: qtyInBase is positive; the base unit's is exactly 1. */
    INVALID_QTY_IN_BASE(VALIDATION),
    INVALID_CONVERSION(VALIDATION),
    UNKNOWN_UNIT(VALIDATION),
    UNKNOWN_CATEGORY(VALIDATION),
    UNKNOWN_VARIANT(VALIDATION),
    UNKNOWN_ATTRIBUTE_VALUE(VALIDATION),
    UNKNOWN_TAX_CATEGORY(VALIDATION),
    /** CAT-09: VARIANT_MATRIX and BRANDED_PACKET products need attributes. */
    ATTRIBUTES_REQUIRED(VALIDATION),
    TOO_MANY_VARIANTS(VALIDATION),
    VARIANT_COMBINATION_EXCLUDED(VALIDATION),
    /** CAT-10: at least one IN and one OUT line. */
    DEFINITION_LINES_INVALID(VALIDATION),
    /** CAT-10: the OUT lines' cost shares sum to 100. */
    COST_SHARE_INVALID(VALIDATION),
    CATEGORY_CYCLE(VALIDATION),
    CATEGORY_TOO_DEEP(VALIDATION);

    private final ErrorCategory category;

    CatalogErrorCode(ErrorCategory category) {
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
        return "catalog";
    }
}
