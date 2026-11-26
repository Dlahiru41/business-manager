package com.bmp.pricing.domain.service;

import com.bmp.pricing.domain.model.TaxRate;
import com.bmp.shared.money.Money;
import com.bmp.shared.money.RoundingPolicy;
import com.bmp.shared.money.TaxSplit;
import java.util.List;

/**
 * Splits a line amount into net and taxes (PRC-07) with the shared {@link TaxSplit}, which sales uses too, so a bill
 * is taxed exactly as it was priced: tax-inclusive amounts are back-calculated, exclusive ones get the taxes on top,
 * compound rates apply to the net plus the taxes before them, each tax is rounded per line and the net absorbs the
 * rounding.
 */
public final class TaxCalculator {

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
        TaxSplit.Split split = TaxSplit.split(
                amount,
                inclusive,
                rates.stream()
                        .map(r -> new TaxSplit.Rate(r.id(), r.code(), r.ratePct(), r.compound()))
                        .toList(),
                rounding);
        return new Split(
                split.net(),
                split.taxes().stream()
                        .map(t -> new LineTax(t.rateId(), t.code(), t.ratePct(), t.amount(), compound(rates, t)))
                        .toList(),
                split.total());
    }

    private static boolean compound(List<TaxRate> rates, TaxSplit.Tax tax) {
        return rates.stream().anyMatch(r -> r.id().equals(tax.rateId()) && r.compound());
    }
}
