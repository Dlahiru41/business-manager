package com.bmp.web.problem;

import com.bmp.shared.error.ErrorCode;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Resolves constraint violations raised by the database to the error codes modules registered for them. */
public class ConstraintErrorRegistry {

    private static final Pattern CONSTRAINT = Pattern.compile("constraint \"([A-Za-z0-9_]+)\"");

    private final Map<String, ErrorCode> codes = new HashMap<>();

    public ConstraintErrorRegistry(List<ConstraintErrorMapping> mappings) {
        for (ConstraintErrorMapping mapping : mappings) {
            ErrorCode previous = codes.putIfAbsent(mapping.constraintName(), mapping.errorCode());
            if (previous != null && !previous.equals(mapping.errorCode())) {
                throw new IllegalStateException("Constraint " + mapping.constraintName() + " is mapped twice");
            }
        }
    }

    /** The error code registered for the constraint named in {@code failure}'s cause chain, if any. */
    public Optional<ErrorCode> resolve(Throwable failure) {
        return constraintName(failure).flatMap(name -> Optional.ofNullable(codes.get(name)));
    }

    /** The violated constraint's name, read from the database message anywhere in the cause chain. */
    static Optional<String> constraintName(Throwable failure) {
        for (Throwable t = failure; t != null; t = next(t)) {
            String message = t.getMessage();
            if (message != null) {
                Matcher matcher = CONSTRAINT.matcher(message);
                if (matcher.find()) {
                    return Optional.of(matcher.group(1));
                }
            }
        }
        return Optional.empty();
    }

    /** The first SQLSTATE found in the cause chain, if any. */
    public static Optional<String> sqlState(Throwable failure) {
        for (Throwable t = failure; t != null; t = next(t)) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return Optional.of(sql.getSQLState());
            }
        }
        return Optional.empty();
    }

    private static @Nullable Throwable next(Throwable t) {
        // Throwable.getCause() already returns null for self-caused throwables.
        return t.getCause();
    }
}
