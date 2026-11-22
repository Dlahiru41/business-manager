package com.bmp.shared.error;

/** Error categories; each maps to one HTTP status family and one log level (04-error-handling.md §2). */
public enum ErrorCategory {
    VALIDATION,
    NOT_FOUND,
    CONFLICT,
    FORBIDDEN,
    PRECONDITION,
    RATE_LIMITED,
    DEPENDENCY,
    INTERNAL
}
