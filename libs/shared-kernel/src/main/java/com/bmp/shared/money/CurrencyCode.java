package com.bmp.shared.money;

import java.util.Currency;
import java.util.Objects;

/**
 * An ISO 4217 currency code validated against {@link java.util.Currency}. Pseudo-currencies without a minor unit
 * (e.g. {@code XXX}) are rejected because money amounts must be roundable.
 *
 * @param code upper-case three-letter ISO 4217 code, e.g. {@code LKR}
 */
public record CurrencyCode(String code) {

    /** Sri Lankan rupee, the pilot tenant's currency. */
    public static final CurrencyCode LKR = new CurrencyCode("LKR");

    public CurrencyCode {
        Objects.requireNonNull(code, "code");
        Currency currency;
        try {
            currency = Currency.getInstance(code);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Not an ISO 4217 currency code: " + code, e);
        }
        if (currency.getDefaultFractionDigits() < 0) {
            throw new IllegalArgumentException("Currency has no minor unit: " + code);
        }
    }

    public static CurrencyCode of(String code) {
        return new CurrencyCode(code);
    }

    /** Number of decimal places of the minor unit (LKR = 2, JPY = 0). */
    public int minorUnits() {
        return Currency.getInstance(code).getDefaultFractionDigits();
    }

    @Override
    public String toString() {
        return code;
    }
}
