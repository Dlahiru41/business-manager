package com.bmp.sales.domain.service;

import com.bmp.shared.money.Allocation;
import com.bmp.shared.money.CurrencyCode;
import com.bmp.shared.money.Money;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/** Rule SAL-04: a bill discount spread over the lines by largest remainder on their net, the parts summing exactly. */
public final class DiscountAllocator {

    private DiscountAllocator() {}

    /**
     * Splits {@code discount} in proportion to {@code lineNets}.
     *
     * @return one share per line, in line order; all zero when there is no discount
     */
    public static List<BigDecimal> allocate(BigDecimal discount, List<BigDecimal> lineNets, CurrencyCode currency) {
        if (discount.signum() == 0 || lineNets.stream().allMatch(n -> n.signum() == 0)) {
            return Collections.nCopies(lineNets.size(), BigDecimal.ZERO);
        }
        return Allocation.largestRemainder(Money.of(discount, currency), lineNets).stream()
                .map(Money::amount)
                .toList();
    }
}
