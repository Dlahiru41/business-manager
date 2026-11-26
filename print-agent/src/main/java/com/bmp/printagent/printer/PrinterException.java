package com.bmp.printagent.printer;

import java.io.Serial;
import org.jspecify.annotations.Nullable;

/** The printer could not take a document; {@link #status()} says why. */
public class PrinterException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    private final PrinterStatus status;

    public PrinterException(PrinterStatus status, String message, @Nullable Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public PrinterStatus status() {
        return status;
    }
}
