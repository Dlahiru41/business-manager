package com.bmp.shared.error;

import java.io.Serial;
import java.util.List;
import java.util.Map;

/** Semantic validation failed on several fields at once; all field errors are collected (422). */
public final class ValidationFailure extends DomainException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient List<FieldError> fieldErrors;

    public ValidationFailure(List<FieldError> fieldErrors) {
        this(PlatformErrorCode.VALIDATION_FAILED, fieldErrors);
    }

    public ValidationFailure(ErrorCode errorCode, List<FieldError> fieldErrors) {
        super(errorCode, Map.of());
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public List<FieldError> fieldErrors() {
        return fieldErrors;
    }
}
