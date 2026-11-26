package com.bmp.shared.security;

import org.jspecify.annotations.Nullable;

/**
 * Recognises payment card numbers so they are never stored (PAY-05, PCI scope): a run of 13–19 digits, spaces and
 * dashes between them ignored, that passes the Luhn check. References, notes and reasons are checked before they are
 * kept anywhere.
 */
public final class CardNumbers {

    private static final int MIN_DIGITS = 13;
    private static final int MAX_DIGITS = 19;

    private CardNumbers() {}

    /** Whether the text contains something that looks like a card number. */
    public static boolean containsCardNumber(@Nullable String text) {
        if (text == null) {
            return false;
        }
        StringBuilder run = new StringBuilder();
        for (int i = 0; i <= text.length(); i++) {
            char c = i < text.length() ? text.charAt(i) : 'x';
            boolean separator = (c == ' ' || c == '-') && !run.isEmpty();
            if (Character.isDigit(c)) {
                run.append(c);
            } else if (!separator) {
                if (looksLikeCard(run)) {
                    return true;
                }
                run.setLength(0);
            }
        }
        return false;
    }

    /** A run of a card number's length passing Luhn; longer runs (bank references) are not card numbers. */
    private static boolean looksLikeCard(CharSequence digits) {
        int length = digits.length();
        return length >= MIN_DIGITS && length <= MAX_DIGITS && luhn(digits, 0, length);
    }

    static boolean luhn(CharSequence digits, int from, int to) {
        int sum = 0;
        boolean twice = false;
        for (int i = to - 1; i >= from; i--) {
            int digit = digits.charAt(i) - '0';
            if (twice) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            twice = !twice;
        }
        return sum % 10 == 0;
    }
}
