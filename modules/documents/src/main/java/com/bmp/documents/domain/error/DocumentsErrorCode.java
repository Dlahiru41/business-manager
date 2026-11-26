package com.bmp.documents.domain.error;

import static com.bmp.shared.error.ErrorCategory.CONFLICT;
import static com.bmp.shared.error.ErrorCategory.NOT_FOUND;
import static com.bmp.shared.error.ErrorCategory.VALIDATION;

import com.bmp.shared.error.ErrorCategory;
import com.bmp.shared.error.ErrorCode;

/** Error codes of the documents module. Codes are never renamed once released. */
public enum DocumentsErrorCode implements ErrorCode {
    BILL_NOT_FOUND(NOT_FOUND),
    PRINT_AGENT_NOT_FOUND(NOT_FOUND),
    PRINT_JOB_NOT_FOUND(NOT_FOUND),
    UNKNOWN_LOCATION(VALIDATION),
    UNKNOWN_PRINT_AGENT(VALIDATION),
    NO_PRINT_AGENT(CONFLICT),
    PRINT_JOB_STATE_INVALID(CONFLICT);

    private final ErrorCategory category;

    DocumentsErrorCode(ErrorCategory category) {
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
        return "documents";
    }
}
