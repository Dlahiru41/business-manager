package com.bmp.sync.application;

import java.io.Serial;

/** A record's payload cannot be read as its type; the record is rejected, never retried (SYN-04). */
final class InvalidPayloadException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    InvalidPayloadException(String type, Throwable cause) {
        super("The payload does not match the " + type + " record", cause);
    }
}
