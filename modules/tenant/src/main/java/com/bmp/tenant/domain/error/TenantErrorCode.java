package com.bmp.tenant.domain.error;

import static com.bmp.shared.error.ErrorCategory.CONFLICT;
import static com.bmp.shared.error.ErrorCategory.NOT_FOUND;
import static com.bmp.shared.error.ErrorCategory.VALIDATION;

import com.bmp.shared.error.ErrorCategory;
import com.bmp.shared.error.ErrorCode;

/** Error codes of the tenant module. Codes are never renamed once released. */
public enum TenantErrorCode implements ErrorCode {
    TENANT_NOT_FOUND(NOT_FOUND),
    INVALID_TENANT_STATUS_TRANSITION(CONFLICT),
    LOCATION_NOT_FOUND(NOT_FOUND),
    LOCATION_NAME_TAKEN(CONFLICT),
    /** TEN-01: a tenant always keeps at least one active location. */
    LAST_ACTIVE_LOCATION(CONFLICT),
    SETTING_GROUP_NOT_FOUND(NOT_FOUND),
    /** TEN-03: settings keys are a closed set per group. */
    UNKNOWN_SETTING(VALIDATION),
    INVALID_SETTING_VALUE(VALIDATION),
    /** TEN-02: currency and time zone are locked once business documents may exist. */
    SETTING_LOCKED(CONFLICT),
    RECEIPT_TEMPLATE_NOT_FOUND(NOT_FOUND),
    RECEIPT_TEMPLATE_NAME_TAKEN(CONFLICT);

    private final ErrorCategory category;

    TenantErrorCode(ErrorCategory category) {
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
        return "tenant";
    }
}
