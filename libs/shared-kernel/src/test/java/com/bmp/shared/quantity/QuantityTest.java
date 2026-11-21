package com.bmp.shared.quantity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.PlatformErrorCode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class QuantityTest {

    private static final UnitCode ML = UnitCode.of("ml");
    private static final UnitCode PIECE = UnitCode.of("piece");

    @Test
    void shouldRejectNullComponents() {
        assertThatNullPointerException().isThrownBy(() -> new Quantity(null, ML));
        assertThatNullPointerException().isThrownBy(() -> new Quantity(BigDecimal.ONE, null));
    }

    @Test
    void shouldCreateFromStringBigDecimalAndZero() {
        assertThat(Quantity.of("187.5", ML).value()).isEqualByComparingTo("187.5");
        assertThat(Quantity.of(BigDecimal.TEN, PIECE).unit()).isEqualTo(PIECE);
        assertThat(Quantity.zero(ML).isZero()).isTrue();
    }

    @Test
    void shouldAddSubtractMultiplyAndNegate() {
        Quantity can = Quantity.of("19565", ML);
        Quantity bottle = Quantity.of("750", ML);

        assertThat(can.minus(bottle).value()).isEqualByComparingTo("18815");
        assertThat(can.plus(bottle).value()).isEqualByComparingTo("20315");
        assertThat(bottle.times(new BigDecimal("2.5")).value()).isEqualByComparingTo("1875");
        assertThat(bottle.negate().value()).isEqualByComparingTo("-750");
    }

    @Test
    void shouldRejectMixingUnits() {
        Quantity ml = Quantity.of("1", ML);
        Quantity piece = Quantity.of("1", PIECE);

        assertThatThrownBy(() -> ml.plus(piece))
                .isInstanceOfSatisfying(
                        BusinessRuleViolation.class,
                        e -> assertThat(e.errorCode()).isEqualTo(PlatformErrorCode.UNIT_MISMATCH));
        assertThatThrownBy(() -> ml.minus(piece)).isInstanceOf(BusinessRuleViolation.class);
        assertThatThrownBy(() -> ml.compareTo(piece)).isInstanceOf(BusinessRuleViolation.class);
    }

    @Test
    void shouldReportSign() {
        assertThat(Quantity.of("1", ML).isPositive()).isTrue();
        assertThat(Quantity.of("1", ML).isNegative()).isFalse();
        assertThat(Quantity.of("1", ML).isZero()).isFalse();
        assertThat(Quantity.of("-1", ML).isNegative()).isTrue();
        assertThat(Quantity.of("-1", ML).isPositive()).isFalse();
        assertThat(Quantity.of("0.000", ML).isZero()).isTrue();
        assertThat(Quantity.of("0.000", ML).isPositive()).isFalse();
        assertThat(Quantity.of("0.000", ML).isNegative()).isFalse();
    }

    @Test
    void shouldCompareAndEqualIgnoringScale() {
        Quantity a = Quantity.of("0.750", ML);

        assertThat(a.isEqualTo(Quantity.of("0.75", ML))).isTrue();
        assertThat(a.isEqualTo(Quantity.of("0.76", ML))).isFalse();
        assertThat(a.isEqualTo(Quantity.of("0.75", PIECE))).isFalse();
        assertThat(a.compareTo(Quantity.of("1", ML))).isNegative();
        assertThat(a).isEqualTo(Quantity.of("0.75", ML));
        assertThat(a).hasSameHashCodeAs(Quantity.of("0.75", ML));
        assertThat(a).isNotEqualTo(Quantity.of("0.75", PIECE));
        assertThat(a).isNotEqualTo("0.75 ml");
        assertThat(a).hasToString("0.750 ml");
    }

    @ParameterizedTest
    @ValueSource(strings = {"piece", "g", "kg", "ml", "tray", "packet_250g", "can-18kg", "l"})
    void shouldAcceptWellFormedUnitCodes(String code) {
        assertThat(UnitCode.of(code)).hasToString(code);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "1kg", "k g", "-ml", "abcdefghijklmnopqrstuvwxyzabcdefg"})
    void shouldRejectMalformedUnitCodes(String code) {
        assertThatIllegalArgumentException().isThrownBy(() -> UnitCode.of(code));
    }
}
