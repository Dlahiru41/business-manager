package com.bmp.shared.error;

import java.io.Serial;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** A downstream system (Keycloak, printer, Kafka, ...) failed or is unavailable. Mapped to 503 and retryable. */
public final class IntegrationException extends BmpException {

    @Serial
    private static final long serialVersionUID = 1L;

    public IntegrationException(ErrorCode errorCode, Map<String, ?> details, @Nullable Throwable cause) {
        super(errorCode, null, details, cause);
    }
}
