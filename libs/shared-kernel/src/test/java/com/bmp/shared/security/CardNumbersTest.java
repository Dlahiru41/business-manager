package com.bmp.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Rule PAY-05: what looks like a card number is never stored. */
class CardNumbersTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "4111111111111111",
                "4111 1111 1111 1111",
                "card 4111-1111-1111-1111 approved",
                "5500005555555559",
                "378282246310005",
                "6011111111111117",
                "4222222222222"
            })
    void pay05CardNumbersAreRecognised(String text) {
        assertThat(CardNumbers.containsCardNumber(text)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "4111111111111112",
                "APPR 123456",
                "slip 0042",
                "123456789012",
                "41111111111111111111111",
                "TRX-2026-11-25-0001",
                ""
            })
    void pay05OtherReferencesAreNot(String text) {
        assertThat(CardNumbers.containsCardNumber(text)).isFalse();
    }

    @Test
    void nothingIsNoCardNumber() {
        assertThat(CardNumbers.containsCardNumber(null)).isFalse();
        assertThat(CardNumbers.luhn("79927398713", 0, 11)).isTrue();
        assertThat(CardNumbers.luhn("79927398710", 0, 11)).isFalse();
    }
}
