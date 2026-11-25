package com.bmp.shared.money;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Splits a line amount into net and taxes (PRC-07, SAL-04). Tax-inclusive amounts are back-calculated, {@code tax =
 * gross × rate / (100 + rate)} for a single rate; exclusive amounts get the taxes on top. Non-compound rates apply to
 * the net amount, compound rates to the net plus the taxes before them. Each tax is rounded per line, and the net
 * absorbs the rounding so net + taxes equals the gross exactly. Pricing quotes and sales bills both use it, so a bill
 * is taxed exactly as it was priced.
 */
public final class TaxSplit {

    private static final MathContext PRECISION = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private TaxSplit() {}

    /** A tax rate valid for the line: a percentage, simple or compound. */
    public record Rate(UUID id, String code, BigDecimal ratePct, boolean compound) {
        public Rate {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(code, "code");
            if (ratePct.signum() < 0) {
                throw new IllegalArgumentException("A tax rate is not negative: " + ratePct);
            }
        }
    }

    /** One rate's tax on the line. */
    public record Tax(UUID rateId, String code, BigDecimal ratePct, Money amount) {}

    /**
     * The split of one line.
     *
     * @param net the amount without tax
     * @param taxes each rate's tax
     * @param total what the customer pays (net + taxes)
     */
    public record Split(Money net, List<Tax> taxes, Money total) {
        public Split {
            taxes = List.copyOf(taxes);
        }

        public Money tax() {
            return total.minus(net);
        }
    }

    /** Splits {@code amount}, which includes tax when {@code inclusive}. */
    public static Split split(Money amount, boolean inclusive, List<Rate> rates, RoundingPolicy rounding) {
        List<Rate> ordered = rates.stream()
                .sorted(Comparator.comparing(Rate::compound).thenComparing(Rate::code))
                .toList();
        if (ordered.isEmpty()) {
            return new Split(amount, List.of(), amount);
        }
        BigDecimal net = inclusive ? amount.amount().divide(grossFactor(ordered), PRECISION) : amount.amount();
        List<Tax> taxes = new ArrayList<>();
        BigDecimal taxedSoFar = BigDecimal.ZERO;
        BigDecimal roundedTaxes = BigDecimal.ZERO;
        for (Rate rate : ordered) {
            BigDecimal base = rate.compound() ? net.add(taxedSoFar) : net;
            BigDecimal exact = base.multiply(rate.ratePct()).divide(HUNDRED, PRECISION);
            taxedSoFar = taxedSoFar.add(exact);
            Money tax = Money.of(exact, amount.currency()).rounded(rounding);
            roundedTaxes = roundedTaxes.add(tax.amount());
            taxes.add(new Tax(rate.id(), rate.code(), rate.ratePct(), tax));
        }
        Money taxTotal = Money.of(roundedTaxes, amount.currency());
        return inclusive
                ? new Split(amount.minus(taxTotal), taxes, amount)
                : new Split(amount, taxes, amount.plus(taxTotal));
    }

    /** How much a net amount grows with all rates: (1 + Σ simple/100) × Π (1 + compound/100). */
    private static BigDecimal grossFactor(List<Rate> rates) {
        BigDecimal simple = BigDecimal.ONE;
        BigDecimal factor = BigDecimal.ONE;
        for (Rate rate : rates) {
            BigDecimal share = rate.ratePct().divide(HUNDRED, PRECISION);
            if (rate.compound()) {
                factor = factor.multiply(BigDecimal.ONE.add(share), PRECISION);
            } else {
                simple = simple.add(share);
            }
        }
        return simple.multiply(factor, PRECISION);
    }
}
