package com.bmp.shared.error;

/**
 * A stable, machine-readable error identifier. Codes are {@code UPPER_SNAKE_CASE} and never renamed once released,
 * because clients branch on them. Each module defines its own enum implementing this interface in
 * {@code domain/error}.
 */
public interface ErrorCode {

    /** The stable code, e.g. {@code VARIANT_NOT_FOUND}. */
    String code();

    /** Decides the HTTP status and log level (04-error-handling.md §2). */
    ErrorCategory category();

    /** The owning module, used in the Problem {@code type} URI, e.g. {@code inventory}. */
    String module();

    /** The i18n message key for the human-readable title. */
    default String titleKey() {
        return "error." + code() + ".title";
    }

    /** The i18n message key for the optional human-readable detail template. */
    default String detailKey() {
        return "error." + code() + ".detail";
    }
}
