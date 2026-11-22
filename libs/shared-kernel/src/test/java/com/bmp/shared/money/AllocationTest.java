package com.bmp.shared.money;

import static com.bmp.shared.money.CurrencyCode.LKR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.PlatformErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class AllocationTest {

    private static List<BigDecimal> weights(String... values) {
        return Arrays.stream(values).map(BigDecimal::new).toList();
    }

    private static List<String> amounts(List<Money> parts) {
        return parts.stream().map(m -> m.amount().toPlainString()).toList();
    }

    private static BigDecimal sum(List<Money> parts) {
        return parts.stream().map(Money::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void shouldGiveLeftoverCentsToLargestRemainders() {
        List<Money> parts = Allocation.largestRemainder(Money.of("10.00", LKR), weights("3", "3", "3"));

        assertThat(amounts(parts)).containsExactly("3.34", "3.33", "3.33");
    }

    @Test
    void shouldAllocateProportionallyToUnequalWeights() {
        // Bill discount of 50.00 across lines worth 100, 250 and 650.
        List<Money> parts = Allocation.largestRemainder(Money.of("50.00", LKR), weights("100", "250", "650"));

        assertThat(amounts(parts)).containsExactly("5.00", "12.50", "32.50");
    }

    @Test
    void shouldPreferLargerRemainderOverEarlierPosition() {
        // exact shares: 0.333.., 0.666.. -> floors 0.33, 0.66, remainders favour the second part
        List<Money> parts = Allocation.largestRemainder(Money.of("1.00", LKR), weights("1", "2"));

        assertThat(amounts(parts)).containsExactly("0.33", "0.67");
    }

    @Test
    void shouldGiveZeroToZeroWeights() {
        List<Money> parts = Allocation.largestRemainder(Money.of("1.00", LKR), weights("0", "1", "0"));

        assertThat(amounts(parts)).containsExactly("0.00", "1.00", "0.00");
    }

    @Test
    void shouldCarryTheSignOfNegativeTotals() {
        List<Money> parts = Allocation.largestRemainder(Money.of("-10.00", LKR), weights("1", "1", "1"));

        assertThat(amounts(parts)).containsExactly("-3.34", "-3.33", "-3.33");
    }

    @Test
    void shouldAllocateZeroTotal() {
        List<Money> parts = Allocation.largestRemainder(Money.zero(LKR), weights("1", "2"));

        assertThat(amounts(parts)).containsExactly("0.00", "0.00");
    }

    @Test
    void shouldKeepPrecisionFinerThanTheMinorUnit() {
        // Cost amounts are stored with 4 decimals; allocation must not lose them.
        List<Money> parts = Allocation.largestRemainder(Money.of("1.0001", LKR), weights("1", "1"));

        assertThat(amounts(parts)).containsExactly("0.5001", "0.5000");
        assertThat(sum(parts)).isEqualByComparingTo("1.0001");
    }

    @Test
    void shouldAcceptFractionalAndScaledWeights() {
        List<Money> parts = Allocation.largestRemainder(Money.of("100.00", LKR), weights("0.25", "1E+1", "0.75"));

        assertThat(sum(parts)).isEqualByComparingTo("100.00");
        assertThat(amounts(parts)).containsExactly("2.27", "90.91", "6.82");
    }

    @Test
    void shouldUseCurrencyMinorUnitsForZeroDecimalCurrencies() {
        List<Money> parts =
                Allocation.largestRemainder(Money.of("100", CurrencyCode.of("JPY")), weights("1", "1", "1"));

        assertThat(amounts(parts)).containsExactly("34", "33", "33");
    }

    @Test
    void shouldRejectEmptyNegativeOrAllZeroWeights() {
        Money total = Money.of("1", LKR);

        for (List<BigDecimal> invalid : List.of(List.<BigDecimal>of(), weights("1", "-1"), weights("0", "0"))) {
            assertThatThrownBy(() -> Allocation.largestRemainder(total, invalid))
                    .isInstanceOfSatisfying(
                            BusinessRuleViolation.class,
                            e -> assertThat(e.errorCode()).isEqualTo(PlatformErrorCode.INVALID_ALLOCATION_WEIGHTS));
        }
    }

    @Test
    void propertyPartsAlwaysSumToTotalAndStayWithinOneUnitOfExactShare() {
        Random random = new Random(20261121L);
        for (int run = 0; run < 2_000; run++) {
            BigDecimal amount = BigDecimal.valueOf(random.nextLong(-10_000_000L, 10_000_000L), 2);
            Money total = Money.of(amount, LKR);
            int count = 1 + random.nextInt(12);
            List<BigDecimal> weights = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                weights.add(BigDecimal.valueOf(random.nextInt(0, 100_000), random.nextInt(0, 4)));
            }
            if (weights.stream().allMatch(w -> w.signum() == 0)) {
                weights.set(0, BigDecimal.ONE);
            }

            List<Money> parts = Allocation.largestRemainder(total, weights);

            assertThat(parts).hasSize(count);
            assertThat(sum(parts)).isEqualByComparingTo(amount);
            BigDecimal weightSum = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            for (int i = 0; i < count; i++) {
                BigDecimal exact = amount.multiply(weights.get(i)).divide(weightSum, 10, RoundingMode.HALF_EVEN);
                BigDecimal difference = parts.get(i).amount().subtract(exact).abs();
                assertThat(difference).isLessThan(new BigDecimal("0.01"));
                assertThat(parts.get(i).amount().signum() * amount.signum()).isGreaterThanOrEqualTo(0);
            }
        }
    }
}
