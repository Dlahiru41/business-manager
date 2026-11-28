package com.bmp.finance.domain.posting;

import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.finance.domain.model.AccountRef;
import com.bmp.finance.domain.model.JournalEntry;
import com.bmp.finance.domain.model.JournalLine;
import com.bmp.finance.domain.model.SystemKey;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;

/**
 * Definition of Done (phase 04): no journal can be unbalanced. Random bills (lines with quantities, prices, discounts
 * and inclusive tax), partial returns, stock issues and adjustments go through the rules; every journal must balance
 * (the builder would throw otherwise) and say what the documents say.
 */
class PostingPropertyTest {

    private static final LocalDate DAY = LocalDate.parse("2026-11-25");

    @RepeatedTest(500)
    void fin03NoJournalCanBeUnbalanced(RepetitionInfo repetition) {
        Random random = new Random(repetition.getCurrentRepetition() * 7919L);
        UUID bill = UUID.randomUUID();
        UUID customer = random.nextBoolean() ? UUID.randomUUID() : null;
        int lineCount = 1 + random.nextInt(8);
        List<SaleCompletedRule.Line> lines = new ArrayList<>();
        List<StockCost> costs = new ArrayList<>();
        List<SaleReturnedRule.Line> returned = new ArrayList<>();
        BigDecimal grand = BigDecimal.ZERO;
        BigDecimal returnTotal = BigDecimal.ZERO;
        for (int i = 0; i < lineCount; i++) {
            BigDecimal qty = BigDecimal.valueOf(100 + random.nextInt(50_000), 3);
            BigDecimal price = BigDecimal.valueOf(100 + random.nextInt(500_000), 2);
            BigDecimal gross = qty.multiply(price).setScale(2, RoundingMode.HALF_UP);
            BigDecimal discount =
                    gross.multiply(BigDecimal.valueOf(random.nextInt(30), 2)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal total = gross.subtract(discount);
            BigDecimal tax = random.nextBoolean()
                    ? total.multiply(new BigDecimal("18")).divide(new BigDecimal("118"), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            UUID variant = UUID.randomUUID();
            UUID line = UUID.randomUUID();
            lines.add(new SaleCompletedRule.Line(line, variant, total, tax, discount));
            grand = grand.add(total);
            costs.add(new StockCost(
                    line,
                    variant,
                    total.multiply(BigDecimal.valueOf(60 + random.nextInt(35), 2))
                            .setScale(4, RoundingMode.HALF_UP)
                            .negate()));
            if (random.nextInt(3) == 0) {
                BigDecimal share = BigDecimal.valueOf(1 + random.nextInt(100), 2);
                BigDecimal amount = total.multiply(share).setScale(2, RoundingMode.HALF_UP);
                BigDecimal returnTax = tax.multiply(share).setScale(2, RoundingMode.HALF_UP);
                returned.add(new SaleReturnedRule.Line(line, variant, amount, returnTax));
                returnTotal = returnTotal.add(amount);
            }
        }
        BigDecimal deviceDifference =
                random.nextInt(10) == 0 ? BigDecimal.valueOf(random.nextInt(5) - 2, 2) : BigDecimal.ZERO;
        BigDecimal paid = grand.add(deviceDifference);

        JournalEntry sale = new SaleCompletedRule()
                .post(new SaleCompletedRule.Sale(
                        UUID.randomUUID(), bill, "R1-1", DAY, customer, UUID.randomUUID(), "RETAIL", paid, lines))
                .orElseThrow();
        Optional<JournalEntry> issue = new StockIssuedRule()
                .post(new StockIssuedRule.Issue(
                        UUID.randomUUID(), "SALE", bill, bill, UUID.randomUUID(), DAY, costs, List.of(), null));
        Optional<JournalEntry> credit = new SaleReturnedRule()
                .post(new SaleReturnedRule.Return(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "CN-1",
                        bill,
                        customer,
                        UUID.randomUUID(),
                        DAY,
                        returnTotal,
                        returned));
        Optional<JournalEntry> waste = new StockAdjustedRule()
                .post(new StockAdjustedRule.Adjustment(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        random.nextBoolean() ? "SPOILAGE" : "OTHER",
                        UUID.randomUUID(),
                        DAY,
                        costs.subList(0, 1)));

        assertBalanced(sale);
        assertThat(amount(sale, customer == null ? SystemKey.POS_CLEARING : SystemKey.AR))
                .isEqualByComparingTo(paid);
        issue.ifPresent(PostingPropertyTest::assertBalanced);
        credit.ifPresent(PostingPropertyTest::assertBalanced);
        BigDecimal refunded = returnTotal;
        credit.ifPresent(c -> assertThat(amount(c, customer == null ? SystemKey.POS_CLEARING : SystemKey.AR))
                .isEqualByComparingTo(refunded.negate()));
        waste.ifPresent(PostingPropertyTest::assertBalanced);
        BigDecimal cost = costs.stream().map(StockCost::costAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(amount(issue.orElseThrow(), SystemKey.COGS)).isEqualByComparingTo(cost.negate());
        assertThat(amount(issue.orElseThrow(), SystemKey.INVENTORY)).isEqualByComparingTo(cost);
    }

    private static void assertBalanced(JournalEntry entry) {
        assertThat(entry.totalDebit()).isEqualByComparingTo(entry.totalCredit());
        assertThat(entry.lines())
                .allSatisfy(l ->
                        assertThat(l.debit().signum() + l.credit().signum()).isEqualTo(1));
    }

    /** Σ debit − credit of an account in the entry. */
    private static BigDecimal amount(JournalEntry entry, SystemKey key) {
        return entry.lines().stream()
                .filter(l -> l.account().equals(AccountRef.of(key)))
                .map(JournalLine::signed)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
