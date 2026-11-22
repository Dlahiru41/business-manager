package com.bmp.shared.money;

import static com.bmp.shared.money.CurrencyCode.LKR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

class CurrencyAndPercentageTest {

    @Test
    void shouldAcceptIsoCurrencies() {
        assertThat(CurrencyCode.of("LKR").minorUnits()).isEqualTo(2);
        assertThat(CurrencyCode.of("JPY").minorUnits()).isZero();
        assertThat(CurrencyCode.of("KWD").minorUnits()).isEqualTo(3);
        assertThat(LKR).hasToString("LKR");
    }

    @Test
    void shouldRejectUnknownLowerCaseAndPseudoCurrencies() {
        assertThatIllegalArgumentException().isThrownBy(() -> CurrencyCode.of("ABC"));
        assertThatIllegalArgumentException().isThrownBy(() -> CurrencyCode.of("lkr"));
        assertThatIllegalArgumentException().isThrownBy(() -> CurrencyCode.of("XXX"));
        assertThatNullPointerException().isThrownBy(() -> new CurrencyCode(null));
    }

    @Test
    void shouldExposeRoundingPolicy() {
        assertThat(RoundingPolicy.defaultPolicy()).isEqualTo(RoundingPolicy.LINE_HALF_UP_2);
        assertThat(RoundingPolicy.LINE_HALF_UP_2.mode()).isEqualTo(RoundingMode.HALF_UP);
        assertThat(RoundingPolicy.LINE_HALF_UP_2.scale()).isEqualTo(2);
        assertThat(RoundingPolicy.LINE_HALF_UP_2.round(new BigDecimal("0.125"))).isEqualByComparingTo("0.13");
    }

    @Test
    void shouldComputePercentagesExactly() {
        Percentage fivePercent = Percentage.of("5.00");

        assertThat(fivePercent.asFraction()).isEqualByComparingTo("0.05");
        assertThat(fivePercent.of(Money.of("1250.00", LKR)).amount()).isEqualByComparingTo("62.5");
        assertThat(Percentage.of("12.5").isGreaterThan(fivePercent)).isTrue();
        assertThat(fivePercent.isGreaterThan(Percentage.of("12.5"))).isFalse();
        assertThat(Percentage.of("100").isAtMostHundred()).isTrue();
        assertThat(Percentage.of("100.01").isAtMostHundred()).isFalse();
        assertThat(fivePercent).hasToString("5.00%");
    }

    @Test
    void shouldComparePercentagesIgnoringScale() {
        assertThat(Percentage.of("5")).isEqualTo(Percentage.of("5.00"));
        assertThat(Percentage.of("5")).hasSameHashCodeAs(Percentage.of("5.00"));
        assertThat(Percentage.of("5")).isNotEqualTo(Percentage.of("5.01"));
        assertThat(Percentage.of("5")).isNotEqualTo("5");
    }

    @Test
    void shouldRejectNegativeOrNullPercentages() {
        assertThatIllegalArgumentException().isThrownBy(() -> Percentage.of("-0.01"));
        assertThatNullPointerException().isThrownBy(() -> new Percentage(null));
    }
}
