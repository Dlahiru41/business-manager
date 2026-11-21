package com.bmp.iam.domain.error;

import static com.bmp.shared.error.ErrorCategory.CONFLICT;
import static com.bmp.shared.error.ErrorCategory.DEPENDENCY;
import static com.bmp.shared.error.ErrorCategory.NOT_FOUND;
import static com.bmp.shared.error.ErrorCategory.VALIDATION;

import com.bmp.shared.error.ErrorCategory;
import com.bmp.shared.error.ErrorCode;

/** Error codes of the iam module. Codes are never renamed once released. */
public enum IamErrorCode implements ErrorCode {
    USER_NOT_FOUND(NOT_FOUND),
    /** The identity provider already has a user with this username. */
    USERNAME_TAKEN(CONFLICT),
    /** IAM-01: a tenant keeps at least one active owner. */
    LAST_OWNER(CONFLICT),
    ROLE_NOT_FOUND(NOT_FOUND),
    ROLE_CODE_TAKEN(CONFLICT),
    /** IAM-02: system roles cannot be removed or deactivated, and the owner role's permissions cannot change. */
    SYSTEM_ROLE_IMMUTABLE(CONFLICT),
    /** A role code that does not exist (or is inactive) in the tenant. */
    UNKNOWN_ROLE(VALIDATION),
    /** A permission grant that is malformed or reserved for the platform. */
    INVALID_PERMISSION(VALIDATION),
    /** A location that does not exist or is inactive in the tenant. */
    INVALID_LOCATION(VALIDATION),
    DEVICE_NOT_FOUND(NOT_FOUND),
    DEVICE_ALREADY_REVOKED(CONFLICT),
    IDENTITY_PROVIDER_UNAVAILABLE(DEPENDENCY);

    private final ErrorCategory category;

    IamErrorCode(ErrorCategory category) {
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
        return "iam";
    }
}
