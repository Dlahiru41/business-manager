package com.bmp.shared.error;

import static com.bmp.shared.error.ErrorCategory.CONFLICT;
import static com.bmp.shared.error.ErrorCategory.DEPENDENCY;
import static com.bmp.shared.error.ErrorCategory.FORBIDDEN;
import static com.bmp.shared.error.ErrorCategory.INTERNAL;
import static com.bmp.shared.error.ErrorCategory.NOT_FOUND;
import static com.bmp.shared.error.ErrorCategory.PRECONDITION;
import static com.bmp.shared.error.ErrorCategory.RATE_LIMITED;
import static com.bmp.shared.error.ErrorCategory.VALIDATION;

/** Error codes owned by the platform itself (kernel, web edge, persistence, security), not by a business module. */
public enum PlatformErrorCode implements ErrorCode {
    // Kernel value objects
    CURRENCY_MISMATCH(VALIDATION),
    UNIT_MISMATCH(VALIDATION),
    INVALID_ALLOCATION_WEIGHTS(VALIDATION),

    // Web edge
    VALIDATION_FAILED(VALIDATION),
    MALFORMED_REQUEST(VALIDATION),
    INVALID_PARAMETER(VALIDATION),
    UNSUPPORTED_MEDIA_TYPE(VALIDATION),
    RESOURCE_NOT_FOUND(NOT_FOUND),
    METHOD_NOT_ALLOWED(CONFLICT),
    IDEMPOTENCY_KEY_REQUIRED(VALIDATION),
    IDEMPOTENCY_KEY_REUSED(VALIDATION),
    REQUEST_IN_PROGRESS(CONFLICT),
    PRECONDITION_REQUIRED(PRECONDITION),
    VERSION_CONFLICT(PRECONDITION),
    RATE_LIMIT_EXCEEDED(RATE_LIMITED),

    // Security
    UNAUTHENTICATED(FORBIDDEN),
    ACCESS_DENIED(FORBIDDEN),
    TENANT_NOT_ACTIVE(FORBIDDEN),
    TENANT_SUSPENDED(FORBIDDEN),
    DEVICE_REVOKED(FORBIDDEN),

    // Infrastructure
    TENANT_CONTEXT_MISSING(INTERNAL),
    DEPENDENCY_UNAVAILABLE(DEPENDENCY),
    INTERNAL_ERROR(INTERNAL);

    private final ErrorCategory category;

    PlatformErrorCode(ErrorCategory category) {
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
        return "platform";
    }
}
