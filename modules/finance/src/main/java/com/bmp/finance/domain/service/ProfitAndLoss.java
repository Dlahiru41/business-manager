package com.bmp.finance.domain.service;

import com.bmp.finance.domain.model.AccountType;
import com.bmp.finance.domain.model.SystemKey;
import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Profit over periods (posting-rules.md §4): (SALES − SALES_RETURNS − SALES_DISCOUNTS) − COGS − WASTAGE −
 * COUNT_VARIANCE − PPV − CASH_OVER_SHORT − operating expenses. Income accounts a tenant adds count as revenue and
 * expense accounts it adds as operating expenses.
 */
public record ProfitAndLoss(
        BigDecimal revenue,
        BigDecimal returns,
        BigDecimal discounts,
        BigDecimal netRevenue,
        BigDecimal costOfGoodsSold,
        BigDecimal wastage,
        BigDecimal countVariance,
        BigDecimal purchasePriceVariance,
        BigDecimal cashOverShort,
        BigDecimal operatingExpenses,
        BigDecimal profit) {

    /** What one account moved in the periods. */
    public record Movement(@Nullable SystemKey key, AccountType type, BigDecimal debit, BigDecimal credit) {}

    public static ProfitAndLoss of(List<Movement> movements) {
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal returns = BigDecimal.ZERO;
        BigDecimal discounts = BigDecimal.ZERO;
        BigDecimal cogs = BigDecimal.ZERO;
        BigDecimal wastage = BigDecimal.ZERO;
        BigDecimal variance = BigDecimal.ZERO;
        BigDecimal ppv = BigDecimal.ZERO;
        BigDecimal overShort = BigDecimal.ZERO;
        BigDecimal operating = BigDecimal.ZERO;
        for (Movement m : movements) {
            BigDecimal debitSide = m.debit().subtract(m.credit());
            if (m.type() == AccountType.INCOME) {
                if (m.key() == SystemKey.SALES_RETURNS) {
                    returns = returns.add(debitSide);
                } else if (m.key() == SystemKey.SALES_DISCOUNTS) {
                    discounts = discounts.add(debitSide);
                } else {
                    revenue = revenue.add(debitSide.negate());
                }
            } else if (m.type() == AccountType.EXPENSE) {
                SystemKey key = m.key();
                if (key == SystemKey.COGS) {
                    cogs = cogs.add(debitSide);
                } else if (key == SystemKey.WASTAGE) {
                    wastage = wastage.add(debitSide);
                } else if (key == SystemKey.COUNT_VARIANCE) {
                    variance = variance.add(debitSide);
                } else if (key == SystemKey.PPV) {
                    ppv = ppv.add(debitSide);
                } else if (key == SystemKey.CASH_OVER_SHORT) {
                    overShort = overShort.add(debitSide);
                } else {
                    operating = operating.add(debitSide);
                }
            }
        }
        BigDecimal net = revenue.subtract(returns).subtract(discounts);
        BigDecimal profit = net.subtract(cogs)
                .subtract(wastage)
                .subtract(variance)
                .subtract(ppv)
                .subtract(overShort)
                .subtract(operating);
        return new ProfitAndLoss(
                revenue, returns, discounts, net, cogs, wastage, variance, ppv, overShort, operating, profit);
    }
}
