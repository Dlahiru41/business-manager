package com.bmp.printagent.cloud;

import java.io.Serial;
import org.jspecify.annotations.Nullable;

/** The cloud could not be reached or refused the device credential; the link tries again later. */
public class CloudUnavailableException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    public CloudUnavailableException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
