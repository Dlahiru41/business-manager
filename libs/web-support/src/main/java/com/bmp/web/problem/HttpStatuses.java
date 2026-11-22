package com.bmp.web.problem;

import com.bmp.shared.error.ErrorCode;
import com.bmp.shared.error.PlatformErrorCode;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/** Maps error codes to HTTP statuses (04-error-handling.md §2 and 03-api-standards.md §9). */
public final class HttpStatuses {

    private HttpStatuses() {}

    public static HttpStatus of(ErrorCode code) {
        if (code instanceof PlatformErrorCode platform) {
            HttpStatus special = special(platform);
            if (special != null) {
                return special;
            }
        }
        return switch (code.category()) {
            case VALIDATION -> HttpStatus.UNPROCESSABLE_CONTENT;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case PRECONDITION -> HttpStatus.PRECONDITION_FAILED;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case DEPENDENCY -> HttpStatus.SERVICE_UNAVAILABLE;
            case INTERNAL -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private static @Nullable HttpStatus special(PlatformErrorCode code) {
        return switch (code) {
            case MALFORMED_REQUEST, INVALID_PARAMETER, IDEMPOTENCY_KEY_REQUIRED -> HttpStatus.BAD_REQUEST;
            case UNSUPPORTED_MEDIA_TYPE -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case PAYLOAD_TOO_LARGE -> HttpStatus.CONTENT_TOO_LARGE;
            case METHOD_NOT_ALLOWED -> HttpStatus.METHOD_NOT_ALLOWED;
            case PRECONDITION_REQUIRED -> HttpStatus.PRECONDITION_REQUIRED;
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            default -> null;
        };
    }
}
