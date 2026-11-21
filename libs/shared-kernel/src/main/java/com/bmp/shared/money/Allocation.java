package com.bmp.shared.money;

import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.PlatformErrorCode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Splits money across weighted parts so the parts always sum exactly to the whole (bill discount across lines, cost
 * across transformation outputs). Works in the smallest unit of the total's scale (at least the currency's minor
 * unit), floors each proportional share, then hands the leftover units to the parts with the largest remainders
 * (ties go to the earlier part).
 */
public final class Allocation {

    private Allocation() {}

    /**
     * Allocates {@code total} proportionally to {@code weights}.
     *
     * @param total the amount to split; its sign is carried by every non-zero part
     * @param weights non-negative weights, at least one positive
     * @return one part per weight, in the same order, summing exactly to {@code total}
     */
    public static List<Money> largestRemainder(Money total, List<BigDecimal> weights) {
        validate(weights);
        int scale = Math.max(
                total.currency().minorUnits(),
                total.amount().stripTrailingZeros().scale());
        BigInteger totalUnits = total.amount().abs().movePointRight(scale).toBigIntegerExact();

        int weightScale = weights.stream()
                .mapToInt(w -> w.stripTrailingZeros().scale())
                .max()
                .orElse(0);
        List<BigInteger> integerWeights = weights.stream()
                .map(w -> w.movePointRight(Math.max(weightScale, 0)).toBigIntegerExact())
                .toList();
        BigInteger weightSum = integerWeights.stream().reduce(BigInteger.ZERO, BigInteger::add);

        List<BigInteger> shares = new ArrayList<>(weights.size());
        List<BigInteger> remainders = new ArrayList<>(weights.size());
        for (BigInteger weight : integerWeights) {
            BigInteger[] quotientAndRemainder = totalUnits.multiply(weight).divideAndRemainder(weightSum);
            shares.add(quotientAndRemainder[0]);
            remainders.add(quotientAndRemainder[1]);
        }

        BigInteger allocated = shares.stream().reduce(BigInteger.ZERO, BigInteger::add);
        int leftover = totalUnits.subtract(allocated).intValueExact();
        IntStream.range(0, weights.size())
                .boxed()
                .sorted(Comparator.<Integer, BigInteger>comparing(remainders::get)
                        .reversed()
                        .thenComparing(Comparator.naturalOrder()))
                .limit(leftover)
                .forEach(i -> shares.set(i, shares.get(i).add(BigInteger.ONE)));

        boolean negative = total.isNegative();
        return shares.stream()
                .map(units -> new BigDecimal(negative ? units.negate() : units, scale))
                .map(amount -> new Money(amount, total.currency()))
                .toList();
    }

    private static void validate(List<BigDecimal> weights) {
        boolean anyNegative = weights.stream().anyMatch(w -> w.signum() < 0);
        boolean anyPositive = weights.stream().anyMatch(w -> w.signum() > 0);
        if (weights.isEmpty() || anyNegative || !anyPositive) {
            throw new BusinessRuleViolation(
                    PlatformErrorCode.INVALID_ALLOCATION_WEIGHTS,
                    "Allocation weights must be non-negative with at least one positive weight",
                    Map.of("weights", weights.size()));
        }
    }
}
