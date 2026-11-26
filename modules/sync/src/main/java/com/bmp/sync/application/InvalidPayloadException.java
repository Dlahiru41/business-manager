package com.bmp.sync.application;

import java.io.Serial;
import java.util.List;

/** A record's payload cannot be read as its type; the record is rejected, never retried (SYN-04). */
final class InvalidPayloadException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient List<String> fields;

    InvalidPayloadException(String type, Throwable cause) {
        super("The payload does not match the " + type + " record", cause);
        this.fields = List.of();
    }

    /** The payload was read, but these fields break its constraints. */
    InvalidPayloadException(String type, List<String> fields) {
        super("The payload does not match the " + type + " record");
        this.fields = List.copyOf(fields);
    }

    /** The fields that broke a constraint; empty when the payload could not be read at all. */
    List<String> fields() {
        return fields;
    }
}
