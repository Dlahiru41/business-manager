package com.bmp.reporting.domain.error;

import static com.bmp.shared.error.ErrorCategory.FORBIDDEN;
import static com.bmp.shared.error.ErrorCategory.NOT_FOUND;
import static com.bmp.shared.error.ErrorCategory.VALIDATION;

import com.bmp.shared.error.ErrorCategory;
import com.bmp.shared.error.ErrorCode;

/** Error codes of the reporting module, listed per operation in {@code reporting.yaml}. */
public enum ReportingErrorCode implements ErrorCode {
    BILL_NOT_REPORTED(NOT_FOUND),
    EXPORT_NOT_FOUND(NOT_FOUND),
    OPERATION_NOT_FOUND(NOT_FOUND),
    INVALID_DATE_RANGE(VALIDATION),
    EXPORT_LINK_INVALID(FORBIDDEN);

    private final ErrorCategory category;

    ReportingErrorCode(ErrorCategory category) {
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
        return "reporting";
    }
}
