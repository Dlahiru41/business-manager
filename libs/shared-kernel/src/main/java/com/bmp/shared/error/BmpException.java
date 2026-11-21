package com.bmp.shared.error;

import java.io.Serial;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Root of every exception the platform throws on purpose. Carries an {@link ErrorCode} and safe-to-expose
 * {@code details} (ids, limits, never personal data) that end up in the Problem Details body.
 */
public abstract sealed class BmpException extends RuntimeException
        permits DomainException, IntegrationException, TechnicalException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient ErrorCode errorCode;
    private final transient Map<String, Object> details;

    protected BmpException(
            ErrorCode errorCode, @Nullable String message, Map<String, ?> details, @Nullable Throwable cause) {
        super(message != null ? message : defaultMessage(errorCode, details), cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        this.details = copy(details);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /** Safe context for clients and logs, in insertion order. */
    public Map<String, Object> details() {
        return details;
    }

    private static Map<String, Object> copy(Map<String, ?> details) {
        Map<String, Object> copy = new LinkedHashMap<>();
        details.forEach((key, value) -> copy.put(Objects.requireNonNull(key), Objects.requireNonNull(value, key)));
        return Collections.unmodifiableMap(copy);
    }

    private static String defaultMessage(ErrorCode errorCode, Map<String, ?> details) {
        return details.isEmpty() ? errorCode.code() : errorCode.code() + " " + details;
    }
}
