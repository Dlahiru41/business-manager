package com.bmp.finance.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.finance.domain.model.AccountRef;
import com.bmp.finance.domain.model.AccountType;
import com.bmp.finance.domain.model.DocumentType;
import com.bmp.finance.domain.model.JournalEntry;
import com.bmp.finance.domain.model.OpenItem;
import com.bmp.finance.domain.model.PartyRef;
import com.bmp.finance.domain.model.SourceType;
import com.bmp.finance.domain.model.SystemKey;
import com.bmp.finance.domain.service.ProfitAndLoss.Movement;
import com.bmp.finance.domain.service.YearEndClosing.Balance;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LedgerServicesTest {

    @ParameterizedTest
    @CsvSource({"-10, 0", "0, 0", "30, 0", "31, 1", "60, 1", "61, 2", "90, 2", "91, 3", "400, 3"})
    void fin07AgingCountsDaysPastTheDueDate(long days, int bucket) {
        Aging.Buckets b = Aging.bucket(BigDecimal.TEN, days);
        List<BigDecimal> all = List.of(b.days0To30(), b.days31To60(), b.days61To90(), b.days90Plus());

        assertThat(all.get(bucket)).isEqualByComparingTo("10");
        assertThat(b.total()).isEqualByComparingTo("10");
    }

    @Test
    void fin07AgingPutsCreditsNotYetAppliedInTheFirstBucket() {
        PartyRef customer = PartyRef.customer(UUID.randomUUID());
        LocalDate asOf = LocalDate.parse("2026-11-30");
        OpenItem old = OpenItem.open(
                UUID.randomUUID(),
                customer,
                DocumentType.BILL,
                UUID.randomUUID(),
                new BigDecimal("500"),
                LocalDate.parse("2026-08-06"),
                LocalDate.parse("2026-09-05"));
        OpenItem credit = OpenItem.open(
                UUID.randomUUID(),
                customer,
                DocumentType.COLLECTION,
                UUID.randomUUID(),
                new BigDecimal("-200"),
                LocalDate.parse("2026-08-01"),
                LocalDate.parse("2026-08-01"));
        old.settle(new BigDecimal("100"));

        Aging.Buckets buckets = Aging.of(List.of(old, credit), asOf);

        assertThat(buckets.days0To30()).isEqualByComparingTo("-200");
        assertThat(buckets.days61To90()).isEqualByComparingTo("400");
        assertThat(buckets.total()).isEqualByComparingTo("200");
        assertThat(Aging.Buckets.ZERO.plus(buckets)).isEqualTo(buckets);
    }

    @Test
    void profitIsNetRevenueLessCostsVariancesAndExpenses() {
        List<Movement> movements = List.of(
                new Movement(SystemKey.SALES, AccountType.INCOME, new BigDecimal("100"), new BigDecimal("152100")),
                new Movement(null, AccountType.INCOME, BigDecimal.ZERO, new BigDecimal("500")),
                new Movement(SystemKey.SALES_RETURNS, AccountType.INCOME, new BigDecimal("1500"), BigDecimal.ZERO),
                new Movement(SystemKey.SALES_DISCOUNTS, AccountType.INCOME, new BigDecimal("800"), BigDecimal.ZERO),
                new Movement(SystemKey.COGS, AccountType.EXPENSE, new BigDecimal("118500"), new BigDecimal("249.60")),
                new Movement(SystemKey.WASTAGE, AccountType.EXPENSE, new BigDecimal("620"), BigDecimal.ZERO),
                new Movement(SystemKey.COUNT_VARIANCE, AccountType.EXPENSE, BigDecimal.ZERO, new BigDecimal("80")),
                new Movement(SystemKey.PPV, AccountType.EXPENSE, new BigDecimal("150"), BigDecimal.ZERO),
                new Movement(SystemKey.CASH_OVER_SHORT, AccountType.EXPENSE, new BigDecimal("100"), BigDecimal.ZERO),
                new Movement(SystemKey.EXP_RENT, AccountType.EXPENSE, new BigDecimal("25000"), BigDecimal.ZERO),
                new Movement(null, AccountType.EXPENSE, new BigDecimal("7000"), BigDecimal.ZERO),
                new Movement(SystemKey.CASH_DRAWER, AccountType.ASSET, new BigDecimal("99999"), BigDecimal.ZERO));

        ProfitAndLoss pl = ProfitAndLoss.of(movements);

        assertThat(pl.revenue()).isEqualByComparingTo("152500");
        assertThat(pl.returns()).isEqualByComparingTo("1500");
        assertThat(pl.discounts()).isEqualByComparingTo("800");
        assertThat(pl.netRevenue()).isEqualByComparingTo("150200");
        assertThat(pl.costOfGoodsSold()).isEqualByComparingTo("118250.40");
        assertThat(pl.wastage()).isEqualByComparingTo("620");
        assertThat(pl.countVariance()).isEqualByComparingTo("-80");
        assertThat(pl.purchasePriceVariance()).isEqualByComparingTo("150");
        assertThat(pl.cashOverShort()).isEqualByComparingTo("100");
        assertThat(pl.operatingExpenses()).isEqualByComparingTo("32000");
        assertThat(pl.profit()).isEqualByComparingTo("-840.40");
    }

    @Test
    void fin06TheYearEndCloseZeroesIncomeAndExpensesAgainstRetainedEarnings() {
        UUID sales = UUID.randomUUID();
        UUID cogs = UUID.randomUUID();
        UUID cash = UUID.randomUUID();
        UUID rent = UUID.randomUUID();
        UUID close = UUID.randomUUID();

        JournalEntry entry = YearEndClosing.close(
                        close,
                        LocalDate.parse("2026-12-31"),
                        "2026",
                        List.of(
                                new Balance(sales, AccountType.INCOME, new BigDecimal("-150000")),
                                new Balance(cogs, AccountType.EXPENSE, new BigDecimal("118000")),
                                new Balance(rent, AccountType.EXPENSE, BigDecimal.ZERO),
                                new Balance(cash, AccountType.ASSET, new BigDecimal("5000"))))
                .orElseThrow();

        assertThat(entry.sourceType()).isEqualTo(SourceType.YEAR_END_CLOSE);
        assertThat(entry.sourceId()).isEqualTo(close);
        assertThat(entry.memo()).isEqualTo("Year-end close 2026");
        assertThat(entry.lines()).hasSize(3);
        assertThat(entry.lines().get(0).account()).isEqualTo(AccountRef.of(sales));
        assertThat(entry.lines().get(0).debit()).isEqualByComparingTo("150000");
        assertThat(entry.lines().get(1).account()).isEqualTo(AccountRef.of(cogs));
        assertThat(entry.lines().get(1).credit()).isEqualByComparingTo("118000");
        assertThat(entry.lines().get(2).account()).isEqualTo(AccountRef.of(SystemKey.RETAINED_EARNINGS));
        assertThat(entry.lines().get(2).credit()).isEqualByComparingTo("32000");
        assertThat(YearEndClosing.close(
                        close,
                        LocalDate.parse("2026-12-31"),
                        "2026",
                        List.of(new Balance(cash, AccountType.ASSET, BigDecimal.ONE))))
                .isEmpty();
    }

    @Test
    void fin01TheDefaultChartHasEveryAccountOfPostingRulesWithUniqueCodes() {
        List<SystemKey> chart = ChartOfAccounts.accounts();

        assertThat(chart).hasSize(29);
        assertThat(chart.stream().map(SystemKey::defaultCode).collect(Collectors.toSet()))
                .hasSize(29);
        assertThat(SystemKey.AR.defaultCode()).isEqualTo("1100");
        assertThat(SystemKey.SALES_RETURNS.type()).isEqualTo(AccountType.INCOME);
        assertThat(SystemKey.EXP_OTHER.defaultCode()).isEqualTo("6900");
        assertThat(SystemKey.EXP_RENT.isOperatingExpense()).isTrue();
        assertThat(SystemKey.COGS.isOperatingExpense()).isFalse();
        assertThat(ChartOfAccounts.expenseCategories())
                .extracting(ChartOfAccounts.DefaultCategory::code)
                .containsExactly("RENT", "UTILITIES", "WAGES", "TRANSPORT", "PACKING", "REPAIRS", "OTHER");
        assertThat(ChartOfAccounts.expenseCategories())
                .allSatisfy(c -> assertThat(c.account().isOperatingExpense()).isTrue());
        assertThat(ChartOfAccounts.expenseCategories().getFirst().name()).isEqualTo("Rent");
        assertThat(Set.copyOf(chart)).contains(SystemKey.POS_CLEARING, SystemKey.GRNI, SystemKey.CUSTOMER_CREDIT);
    }
}
