package com.bmp.shared.quantity;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The code of a unit of measure or packaging unit, e.g. {@code piece}, {@code g}, {@code ml}, {@code tray}. Units are
 * data configured per tenant; this type only guarantees a well-formed code.
 *
 * @param code 1–32 characters: letters, digits, {@code _ . -}, starting with a letter
 */
public record UnitCode(String code) {

    private static final Pattern FORMAT = Pattern.compile("[A-Za-z][A-Za-z0-9_.\\-]{0,31}");

    public UnitCode {
        Objects.requireNonNull(code, "code");
        if (!FORMAT.matcher(code).matches()) {
            throw new IllegalArgumentException("Malformed unit code: " + code);
        }
    }

    public static UnitCode of(String code) {
        return new UnitCode(code);
    }

    @Override
    public String toString() {
        return code;
    }
}
