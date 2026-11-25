package com.bmp.sales.domain.service;

import com.bmp.sales.domain.model.BillDiscount;
import com.bmp.sales.domain.model.DiscountType;
import com.bmp.sales.domain.model.Totals;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.shared.money.CurrencyCode;
import com.bmp.shared.money.Money;
import com.bmp.shared.money.RoundingPolicy;
import com.bmp.shared.money.TaxSplit;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Rule SAL-04: line = round(qty × unit price) − line discount; the bill discount is allocated across the lines by
 * largest remainder on their net; each line is then taxed as it was priced (PRC-07, inclusive or exclusive); the grand
 * total is the sum of the line totals. The server always recomputes with this.
 */
public final class BillTotalsCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private BillTotalsCalculator() {}

    /**
     * A line as priced.
     *
     * @param unitPrice per packaging unit, as the price list says (inclusive of tax when {@code taxInclusive})
     * @param lineDiscount an amount off this line
     */
    public record PricedLine(
            UUID lineId,
            BigDecimal qty,
            BigDecimal unitPrice,
            BigDecimal lineDiscount,
            boolean taxInclusive,
            List<TaxSplit.Rate> rates) {

        public PricedLine {
            rates = List.copyOf(rates);
        }
    }

    /** What a line comes to. */
    public record LineAmounts(
            UUID lineId,
            BigDecimal gross,
            BigDecimal lineDiscount,
            BigDecimal allocatedDiscount,
            BigDecimal tax,
            BigDecimal lineTotal) {}

    /** The lines' amounts, the bill discount as an amount and the totals. */
    public record Result(List<LineAmounts> lines, BigDecimal billDiscount, Totals totals) {
        public Result {
            lines = List.copyOf(lines);
        }
    }

    public static Result calculate(
            List<PricedLine> lines, @Nullable BillDiscount discount, CurrencyCode currency, RoundingPolicy rounding) {
        List<BigDecimal> gross = new ArrayList<>();
        List<BigDecimal> nets = new ArrayList<>();
        for (PricedLine line : lines) {
            BigDecimal amount = rounding.round(line.qty().multiply(line.unitPrice()));
            if (line.lineDiscount().signum() < 0 || line.lineDiscount().compareTo(amount) > 0) {
                throw invalid("A line discount is between 0 and the line amount", line.lineId());
            }
            gross.add(amount);
            nets.add(amount.subtract(line.lineDiscount()));
        }
        BigDecimal netSum = nets.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal billDiscount = billDiscount(discount, netSum, rounding);
        List<BigDecimal> shares = DiscountAllocator.allocate(billDiscount, nets, currency);
        List<LineAmounts> amounts = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal discounts = billDiscount;
        BigDecimal taxes = BigDecimal.ZERO;
        BigDecimal grand = BigDecimal.ZERO;
        for (int i = 0; i < lines.size(); i++) {
            PricedLine line = lines.get(i);
            BigDecimal afterDiscounts = nets.get(i).subtract(shares.get(i));
            TaxSplit.Split split =
                    TaxSplit.split(Money.of(afterDiscounts, currency), line.taxInclusive(), line.rates(), rounding);
            BigDecimal tax = split.tax().amount();
            BigDecimal total = split.total().amount();
            amounts.add(new LineAmounts(line.lineId(), gross.get(i), line.lineDiscount(), shares.get(i), tax, total));
            subtotal = subtotal.add(gross.get(i));
            discounts = discounts.add(line.lineDiscount());
            taxes = taxes.add(tax);
            grand = grand.add(total);
        }
        return new Result(amounts, billDiscount, new Totals(subtotal, discounts, taxes, grand));
    }

    private static BigDecimal billDiscount(
            @Nullable BillDiscount discount, BigDecimal netSum, RoundingPolicy rounding) {
        if (discount == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal value = discount.value();
        BigDecimal amount = discount.type() == DiscountType.PERCENT
                ? rounding.round(netSum.multiply(value).divide(HUNDRED))
                : rounding.round(value);
        boolean percentOutOfRange = discount.type() == DiscountType.PERCENT && value.compareTo(HUNDRED) > 0;
        if (value.signum() < 0 || percentOutOfRange || amount.compareTo(netSum) > 0) {
            throw new BusinessRuleViolation(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A bill discount is at most the lines' net",
                    Map.of("field", "billDiscount"));
        }
        return amount;
    }

    private static BusinessRuleViolation invalid(String message, UUID lineId) {
        return new BusinessRuleViolation(PlatformErrorCode.VALIDATION_FAILED, message, Map.of("lineId", lineId));
    }
}
