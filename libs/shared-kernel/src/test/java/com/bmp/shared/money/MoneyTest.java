package com.bmp.shared.money;

import static com.bmp.shared.money.CurrencyCode.LKR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.shared.quantity.Quantity;
import com.bmp.shared.quantity.UnitCode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MoneyTest {

    private static final CurrencyCode USD = CurrencyCode.of("USD");

    @Test
    void shouldRejectNullComponents() {
        assertThatNullPointerException().isThrownBy(() -> new Money(null, LKR));
        assertThatNullPointerException().isThrownBy(() -> new Money(BigDecimal.ONE, null));
    }

    @Test
    void shouldCreateFromStringBigDecimalAndZero() {
        assertThat(Money.of("1250.00", LKR).amount()).isEqualByComparingTo("1250");
        assertThat(Money.of(new BigDecimal("2.5"), LKR).amount()).isEqualByComparingTo("2.5");
        assertThat(Money.zero(LKR).isZero()).isTrue();
        assertThat(Money.zero(LKR).currency()).isEqualTo(LKR);
    }

    @Test
    void shouldAddAndSubtractAtFullPrecision() {
        Money a = Money.of("10.125", LKR);
        Money b = Money.of("0.0005", LKR);

        assertThat(a.plus(b).amount()).isEqualByComparingTo("10.1255");
        assertThat(a.minus(b).amount()).isEqualByComparingTo("10.1245");
    }

    @Test
    void shouldRejectArithmeticAcrossCurrencies() {
        Money lkr = Money.of("1", LKR);
        Money usd = Money.of("1", USD);

        assertThatThrownBy(() -> lkr.plus(usd))
                .isInstanceOfSatisfying(
                        BusinessRuleViolation.class,
                        e -> assertThat(e.errorCode()).isEqualTo(PlatformErrorCode.CURRENCY_MISMATCH))
                .hasMessageContaining("LKR")
                .hasMessageContaining("USD");
        assertThatThrownBy(() -> lkr.minus(usd)).isInstanceOf(BusinessRuleViolation.class);
        assertThatThrownBy(() -> lkr.compareTo(usd)).isInstanceOf(BusinessRuleViolation.class);
    }

    @Test
    void shouldMultiplyByFactorAndQuantity() {
        Money unitPrice = Money.of("62.50", LKR);

        assertThat(unitPrice.times(new BigDecimal("0.75")).amount()).isEqualByComparingTo("46.875");
        assertThat(unitPrice.times(Quantity.of("3", UnitCode.of("piece"))).amount())
                .isEqualByComparingTo("187.50");
    }

    @Test
    void shouldNegate() {
        assertThat(Money.of("5", LKR).negate().amount()).isEqualByComparingTo("-5");
    }

    @ParameterizedTest
    @CsvSource({"2.345, 2.35", "2.344, 2.34", "2.355, 2.36", "-2.345, -2.35", "0.005, 0.01", "1999.995, 2000.00"})
    void shouldRoundLineHalfUpToTwoDecimals(String raw, String expected) {
        Money rounded = Money.of(raw, LKR).rounded(RoundingPolicy.LINE_HALF_UP_2);

        assertThat(rounded.amount()).isEqualByComparingTo(expected);
        assertThat(rounded.amount().scale()).isEqualTo(2);
    }

    @Test
    void shouldAllocateThroughLargestRemainder() {
        List<Money> parts = Money.of("100", LKR).allocate(List.of(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE));

        assertThat(parts)
                .extracting(Money::amount)
                .map(BigDecimal::toPlainString)
                .containsExactly("33.34", "33.33", "33.33");
    }

    @Test
    void shouldReportSign() {
        Money positive = Money.of("0.01", LKR);
        Money negative = Money.of("-0.01", LKR);
        Money zero = Money.of("0.00", LKR);

        assertThat(positive.isPositive()).isTrue();
        assertThat(positive.isNegative()).isFalse();
        assertThat(positive.isZero()).isFalse();
        assertThat(negative.isNegative()).isTrue();
        assertThat(negative.isPositive()).isFalse();
        assertThat(zero.isZero()).isTrue();
        assertThat(zero.isPositive()).isFalse();
        assertThat(zero.isNegative()).isFalse();
    }

    @Test
    void shouldCompareNumericallyIgnoringScale() {
        Money one = Money.of("1.0", LKR);
        Money oneWithScale = Money.of("1.00", LKR);
        Money two = Money.of("2", LKR);

        assertThat(one.isEqualTo(oneWithScale)).isTrue();
        assertThat(one.isEqualTo(two)).isFalse();
        assertThat(one.isEqualTo(Money.of("1.0", USD))).isFalse();
        assertThat(one.compareTo(two)).isNegative();
        assertThat(two.compareTo(one)).isPositive();
        assertThat(one.compareTo(oneWithScale)).isZero();
    }

    @Test
    void shouldImplementValueEqualityIgnoringScale() {
        Money one = Money.of("1.0", LKR);

        assertThat(one).isEqualTo(Money.of("1.00", LKR));
        assertThat(one).hasSameHashCodeAs(Money.of("1.00", LKR));
        assertThat(one).isNotEqualTo(Money.of("1.0", USD));
        assertThat(one).isNotEqualTo(Money.of("1.01", LKR));
        assertThat(one).isNotEqualTo("1.0 LKR");
        assertThat(Money.of("0.00", LKR)).hasSameHashCodeAs(Money.zero(LKR));
    }

    @Test
    void shouldRenderPlainAmountAndCurrency() {
        assertThat(Money.of("1E+3", LKR)).hasToString("1000 LKR");
        assertThat(Money.of("1250.50", LKR)).hasToString("1250.50 LKR");
    }

    @Test
    void shouldHandleLargeValuesWithoutPrecisionLoss() {
        Money big = Money.of("999999999999999.9999", LKR);

        assertThat(big.plus(Money.of("0.0001", LKR)).amount()).isEqualByComparingTo("1000000000000000");
    }
}
