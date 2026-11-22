package com.bmp.web.pagination;

import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.PlatformErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Opaque cursors: base64url of the last item's sort key values followed by its id (03-api-standards.md §7). Clients
 * must treat cursors as opaque; a tampered or malformed cursor is 400 {@code INVALID_PARAMETER}.
 */
public final class CursorCodec {

    private static final char SEPARATOR = '\u001F';
    private static final String SEPARATOR_STRING = String.valueOf(SEPARATOR);

    private CursorCodec() {}

    public static String encode(List<String> values) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("A cursor needs at least one value");
        }
        for (String value : values) {
            if (value.indexOf(SEPARATOR) >= 0) {
                throw new IllegalArgumentException("Cursor values must not contain the unit separator");
            }
        }
        byte[] raw = String.join(SEPARATOR_STRING, values).getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** Decodes a cursor that must hold exactly {@code expectedValues} values. */
    public static List<String> decode(String cursor, int expectedValues) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            List<String> values =
                    List.of(Pattern.compile(SEPARATOR_STRING, Pattern.LITERAL).split(raw, -1));
            if (values.size() != expectedValues) {
                throw invalid();
            }
            return values;
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
    }

    private static BusinessRuleViolation invalid() {
        return new BusinessRuleViolation(
                PlatformErrorCode.INVALID_PARAMETER, "The cursor is invalid", Map.of("parameter", "cursor"));
    }
}
