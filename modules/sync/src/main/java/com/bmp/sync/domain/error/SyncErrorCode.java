package com.bmp.sync.domain.error;

import static com.bmp.shared.error.ErrorCategory.FORBIDDEN;
import static com.bmp.shared.error.ErrorCategory.VALIDATION;

import com.bmp.shared.error.ErrorCategory;
import com.bmp.shared.error.ErrorCode;

/** Error codes of the sync module. Codes are never renamed once released. */
public enum SyncErrorCode implements ErrorCode {
    DEVICE_REQUIRED(FORBIDDEN),
    DEVICE_NOT_BOUND(FORBIDDEN),
    DEPENDENCY_REJECTED(VALIDATION),
    BATCH_TOO_LARGE(VALIDATION);

    private final ErrorCategory category;

    SyncErrorCode(ErrorCategory category) {
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
        return "sync";
    }
}
