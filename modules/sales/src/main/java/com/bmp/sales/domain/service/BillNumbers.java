package com.bmp.sales.domain.service;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rule SAL-01: bill numbers are {@code <registerPrefix>-<6-digit sequence>}, allocated on the till per register. */
public final class BillNumbers {

    private static final Pattern NUMBER = Pattern.compile("^([A-Z0-9]{1,8})-([0-9]{6})$");

    private BillNumbers() {}

    /** A parsed bill number. */
    public record BillNumber(String prefix, long sequence) {}

    public static Optional<BillNumber> parse(String number) {
        Matcher m = NUMBER.matcher(number);
        return m.matches() ? Optional.of(new BillNumber(m.group(1), Long.parseLong(m.group(2)))) : Optional.empty();
    }

    public static String format(String prefix, long sequence) {
        return prefix + "-" + "%06d".formatted(sequence);
    }
}
