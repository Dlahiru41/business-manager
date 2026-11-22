package com.bmp.shared.error;

import java.util.Objects;

/**
 * One invalid field in a request.
 *
 * @param field JSON path of the field, e.g. {@code lines[0].qty}
 * @param code stable code, e.g. {@code MUST_BE_POSITIVE}
 * @param message human-readable explanation (no personal data)
 */
public record FieldError(String field, String code, String message) {

    public FieldError {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }
}
