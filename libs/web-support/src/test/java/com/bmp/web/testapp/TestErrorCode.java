package com.bmp.web.testapp;

import com.bmp.shared.error.ErrorCategory;
import com.bmp.shared.error.ErrorCode;

public enum TestErrorCode implements ErrorCode {
    NAME_INVALID(ErrorCategory.VALIDATION),
    THING_NOT_FOUND(ErrorCategory.NOT_FOUND),
    THING_LOCKED(ErrorCategory.CONFLICT),
    THING_NAME_TAKEN(ErrorCategory.CONFLICT),
    PRINTER_UNREACHABLE(ErrorCategory.DEPENDENCY);

    private final ErrorCategory category;

    TestErrorCode(ErrorCategory category) {
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
        return "test";
    }
}
