package com.bmp.shared.error;

import java.io.Serial;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** A business rule rejected the operation: 409 for {@code CONFLICT} codes, 422 for {@code VALIDATION} codes. */
public final class BusinessRuleViolation extends DomainException {

    @Serial
    private static final long serialVersionUID = 1L;

    public BusinessRuleViolation(ErrorCode errorCode) {
        super(errorCode);
    }

    public BusinessRuleViolation(ErrorCode errorCode, Map<String, ?> details) {
        super(errorCode, details);
    }

    public BusinessRuleViolation(ErrorCode errorCode, @Nullable String message, Map<String, ?> details) {
        super(errorCode, message, details);
    }
}
