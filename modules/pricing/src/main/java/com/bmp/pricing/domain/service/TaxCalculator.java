package com.bmp.pricing.domain.service;

import com.bmp.pricing.domain.model.TaxRate;
import com.bmp.shared.money.Money;
import com.bmp.shared.money.RoundingPolicy;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Splits a line amount into net and taxes (PRC-07). Tax-inclusive amounts are back-calculated, {@code tax = gross ×
 * rate / (100 + rate)} for a single rate; exclusive amounts get the taxes on top. Non-compound rates apply to the net
 * amount, compound rates to the net plus the taxes before them. Each tax is rounded per line, and the net absorbs the
 * rounding so net + taxes equals the gross exactly.
 */
public final class TaxCalculator {

    private static final MathContext PRECISION = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private TaxCalculator() {}

    /**
     * The split of one line.
     *
     * @param net the amount without tax
     * @param taxes each rate's tax
     * @param total what the customer pays (net + taxes)
     */
    public record Split(Money net, List<LineTax> taxes, Money total) {
        public Split {
            taxes = List.copyOf(taxes);
        }

        public Money tax() {
            return total.minus(net);
        }
    }

    /**
     * Splits {@code amount}, which includes tax when {@code inclusive}.
     *
     * @param rates the rates of the line's tax category valid on the business date
     */
    public static Split split(Money amount, boolean inclusive, List<TaxRate> rates, RoundingPolicy rounding) {
        List<TaxRate> ordered = rates.stream()
                .sorted(Comparator.comparing(TaxRate::compound).thenComparing(TaxRate::code))
                .toList();
        if (ordered.isEmpty()) {
            return new Split(amount, List.of(), amount);
        }
        BigDecimal net = inclusive ? amount.amount().divide(grossFactor(ordered), PRECISION) : amount.amount();
        List<LineTax> taxes = new ArrayList<>();
        BigDecimal taxedSoFar = BigDecimal.ZERO;
        BigDecimal roundedTaxes = BigDecimal.ZERO;
        for (TaxRate rate : ordered) {
            BigDecimal base = rate.compound() ? net.add(taxedSoFar) : net;
            BigDecimal exact = base.multiply(rate.ratePct()).divide(HUNDRED, PRECISION);
            taxedSoFar = taxedSoFar.add(exact);
            Money tax = Money.of(exact, amount.currency()).rounded(rounding);
            roundedTaxes = roundedTaxes.add(tax.amount());
            taxes.add(new LineTax(rate.id(), rate.code(), rate.ratePct(), tax));
        }
        Money taxTotal = Money.of(roundedTaxes, amount.currency());
        return inclusive
                ? new Split(amount.minus(taxTotal), taxes, amount)
                : new Split(amount, taxes, amount.plus(taxTotal));
    }

    /** How much a net amount grows with all rates: (1 + Σ simple/100) × Π (1 + compound/100). */
    private static BigDecimal grossFactor(List<TaxRate> rates) {
        BigDecimal simple = BigDecimal.ONE;
        BigDecimal factor = BigDecimal.ONE;
        for (TaxRate rate : rates) {
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
