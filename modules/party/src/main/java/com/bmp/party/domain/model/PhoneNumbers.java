package com.bmp.party.domain.model;

import com.bmp.party.domain.error.PartyErrorCode;
import com.bmp.shared.error.BusinessRuleViolation;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Rule PTY-01: phones are stored in E.164 form. A number typed without {@code +} or {@code 00} is read in the default
 * country: a leading {@code 0} is the national trunk prefix ({@code 077 123 4567} → {@code +94771234567}); digits
 * that start with the country code and are longer than ten are already international ({@code 94771234567}).
 */
public final class PhoneNumbers {

    /** Sri Lanka, the default country of PTY-01 (ADR-022). */
    public static final String DEFAULT_CALLING_CODE = "94";

    private static final Pattern TYPED = Pattern.compile("[+0-9 ()\\-]{6,24}");
    private static final Pattern PARTIAL = Pattern.compile("[+0-9 ()\\-]{3,24}");
    private static final Pattern E164 = Pattern.compile("\\+[1-9][0-9]{6,14}");
    private static final Pattern NOT_DIGIT = Pattern.compile("[^0-9]");
    private static final int NATIONAL_LENGTH_LIMIT = 10;

    private PhoneNumbers() {}

    /** The E.164 form of a typed phone; {@code INVALID_PHONE} when it cannot be one. */
    public static String normalise(String typed, String callingCode) {
        String trimmed = typed.strip();
        if (!TYPED.matcher(trimmed).matches()) {
            throw invalid();
        }
        String e164 = international(trimmed, callingCode, true);
        if (!E164.matcher(e164).matches()) {
            throw invalid();
        }
        return e164;
    }

    /** The E.164 prefix the start of a phone typed in a search stands for; empty when it is not a phone. */
    public static Optional<String> searchPrefix(String typed, String callingCode) {
        String trimmed = typed.strip();
        if (!PARTIAL.matcher(trimmed).matches()
                || NOT_DIGIT.matcher(trimmed).replaceAll("").length() < 3) {
            return Optional.empty();
        }
        return Optional.of(international(trimmed, callingCode, false));
    }

    private static String international(String typed, String callingCode, boolean complete) {
        String digits = NOT_DIGIT.matcher(typed).replaceAll("");
        if (typed.startsWith("+")) {
            return "+" + digits;
        }
        if (digits.startsWith("00")) {
            return "+" + digits.substring(2);
        }
        if (digits.startsWith("0")) {
            return "+" + callingCode + digits.substring(1);
        }
        if (complete && digits.startsWith(callingCode) && digits.length() > NATIONAL_LENGTH_LIMIT) {
            return "+" + digits;
        }
        return "+" + callingCode + digits;
    }

    private static BusinessRuleViolation invalid() {
        // The number itself is personal data: it is not echoed in the problem details.
        return new BusinessRuleViolation(PartyErrorCode.INVALID_PHONE, "Not a phone number", Map.of("field", "phone"));
    }
}
