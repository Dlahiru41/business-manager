package com.bmp.shared.error;

import java.io.Serial;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** A business rule said no. Rolls back the surrounding transaction; mapped to 4xx by its category. */
public non-sealed class DomainException extends BmpException {

    @Serial
    private static final long serialVersionUID = 1L;

    public DomainException(ErrorCode errorCode) {
        this(errorCode, Map.of());
    }

    public DomainException(ErrorCode errorCode, Map<String, ?> details) {
        this(errorCode, null, details);
    }

    public DomainException(ErrorCode errorCode, @Nullable String message, Map<String, ?> details) {
        super(errorCode, message, details, null);
    }
}
