package com.bmp.shared.error;

import java.io.Serial;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** An invariant was broken: a bug or a misconfiguration. Mapped to 500 and logged at ERROR. */
public final class TechnicalException extends BmpException {

    @Serial
    private static final long serialVersionUID = 1L;

    public TechnicalException(ErrorCode errorCode) {
        this(errorCode, Map.of(), null);
    }

    public TechnicalException(ErrorCode errorCode, Map<String, ?> details, @Nullable Throwable cause) {
        super(errorCode, null, details, cause);
    }
}
