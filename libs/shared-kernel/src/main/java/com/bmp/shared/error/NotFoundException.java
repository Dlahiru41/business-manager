package com.bmp.shared.error;

import java.io.Serial;
import java.util.Map;

/** The resource does not exist or belongs to another tenant (never reveal which). Always 404. */
public final class NotFoundException extends DomainException {

    @Serial
    private static final long serialVersionUID = 1L;

    public NotFoundException(ErrorCode errorCode, Map<String, ?> details) {
        super(errorCode, details);
    }
}
