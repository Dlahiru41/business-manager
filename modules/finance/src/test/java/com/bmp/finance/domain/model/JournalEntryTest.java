package com.bmp.finance.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.finance.domain.error.FinanceErrorCode;
import com.bmp.shared.error.TechnicalException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JournalEntryTest {

    private static final UUID SOURCE = UUID.fromString("0194a100-0000-7000-8000-000000000001");
    private static final LocalDate DAY = LocalDate.parse("2026-11-25");

    private static JournalEntry.Builder builder() {
        return JournalEntry.builder(SourceType.MANUAL, SOURCE, null, DAY);
    }

    @Test
    void fin03TheBuilderRefusesAnUnbalancedEntryWithLedgerImbalance() {
        JournalEntry.Builder unbalanced = builder()
                .debit(SystemKey.CASH_DRAWER, new BigDecimal("100.00"), Dimensions.NONE)
                .credit(SystemKey.SALES, new BigDecimal("99.99"), Dimensions.NONE);

        assertThatThrownBy(unbalanced::build).isInstanceOfSatisfying(TechnicalException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(FinanceErrorCode.LEDGER_IMBALANCE);
            assertThat(e.details())
                    .containsEntry("sourceType", "MANUAL")
                    .containsEntry("sourceId", SOURCE)
                    .containsEntry("debit", "100.0000")
                    .containsEntry("credit", "99.9900");
        });
        assertThatThrownBy(unbalanced::buildIfAny).isInstanceOf(TechnicalException.class);
    }

    @Test
    void fin03ABalancedEntryKeepsItsLinesAtFourDecimals() {
        JournalEntry entry = builder()
                .memo("Owner's capital")
                .debit(SystemKey.BANK, new BigDecimal("100000"), Dimensions.NONE)
                .credit(SystemKey.EQUITY, new BigDecimal("100000.00"), Dimensions.NONE)
                .build();

        assertThat(entry.totalDebit()).isEqualByComparingTo("100000");
        assertThat(entry.totalCredit()).isEqualByComparingTo("100000");
        assertThat(entry.lines()).extracting(l -> l.debit().scale()).containsOnly(JournalEntry.SCALE);
        assertThat(entry.memo()).isEqualTo("Owner's capital");
        assertThat(entry.sourceType()).isEqualTo(SourceType.MANUAL);
        assertThat(entry.sourceId()).isEqualTo(SOURCE);
        assertThat(entry.sourceEventId()).isNull();
        assertThat(entry.entryDate()).isEqualTo(DAY);
        assertThat(entry.reversesId()).isNull();
    }

    @Test
    void aNegativeDebitIsACreditAndZeroAddsNothing() {
        JournalEntry entry = builder()
                .debit(SystemKey.INVENTORY, new BigDecimal("-529.50"), Dimensions.NONE)
                .credit(SystemKey.COGS, new BigDecimal("-529.50"), Dimensions.NONE)
                .debit(SystemKey.WASTAGE, BigDecimal.ZERO, Dimensions.NONE)
                .credit(SystemKey.WASTAGE, new BigDecimal("0.00001"), Dimensions.NONE)
                .build();

        assertThat(entry.lines()).hasSize(2);
        assertThat(entry.lines().get(0).account()).isEqualTo(AccountRef.of(SystemKey.INVENTORY));
        assertThat(entry.lines().get(0).credit()).isEqualByComparingTo("529.50");
        assertThat(entry.lines().get(0).debit()).isZero();
        assertThat(entry.lines().get(1).account()).isEqualTo(AccountRef.of(SystemKey.COGS));
        assertThat(entry.lines().get(1).debit()).isEqualByComparingTo("529.50");
        assertThat(entry.lines().get(1).signed()).isEqualByComparingTo("529.50");
        assertThat(entry.lines().get(0).signed()).isEqualByComparingTo("-529.50");
    }

    @Test
    void amountsAreRoundedHalfUpToFourDecimals() {
        JournalEntry entry = builder()
                .debit(AccountRef.of(SystemKey.COGS), new BigDecimal("10.00005"))
                .credit(AccountRef.of(SystemKey.INVENTORY), new BigDecimal("10.0001"))
                .build();

        assertThat(entry.totalDebit()).isEqualByComparingTo("10.0001");
    }

    @Test
    void anEntryWithNothingToPostIsEmpty() {
        JournalEntry.Builder nothing = builder().debit(SystemKey.WASTAGE, BigDecimal.ZERO, Dimensions.NONE);

        assertThat(nothing.isEmpty()).isTrue();
        assertThat(nothing.buildIfAny()).isEmpty();
        assertThat(builder()
                        .debit(SystemKey.WASTAGE, BigDecimal.ONE, Dimensions.NONE)
                        .isEmpty())
                .isFalse();
    }

    @Test
    void partiesAndDimensionsStayOnTheirLines() {
        UUID customer = UUID.randomUUID();
        UUID bill = UUID.randomUUID();
        Dimensions dims = Dimensions.bill(bill, null, "RETAIL");
        JournalEntry entry = builder()
                .debit(SystemKey.AR, BigDecimal.TEN, PartyRef.customer(customer), dims)
                .credit(SystemKey.SALES, BigDecimal.TEN, null, dims)
                .build();

        assertThat(entry.lines().get(0).party()).isEqualTo(new PartyRef(PartyType.CUSTOMER, customer));
        assertThat(entry.lines().get(0).dimensions().billId()).isEqualTo(bill);
        assertThat(entry.lines().get(1).party()).isNull();
        assertThat(entry.lines().get(1).dimensions().channel()).isEqualTo("RETAIL");
    }

    @Test
    void fin05ALatePostingIsEnteredOnTheOpenDayAndSaysWhy() {
        JournalEntry entry = builder()
                .debit(SystemKey.BANK, BigDecimal.ONE, Dimensions.NONE)
                .credit(SystemKey.EQUITY, BigDecimal.ONE, Dimensions.NONE)
                .build();
        JournalEntry withMemo = builder()
                .memo("Bill R1-000001")
                .debit(SystemKey.BANK, BigDecimal.ONE, Dimensions.NONE)
                .credit(SystemKey.EQUITY, BigDecimal.ONE, Dimensions.NONE)
                .build();
        LocalDate open = LocalDate.parse("2026-12-01");

        assertThat(entry.latePosting(open).entryDate()).isEqualTo(open);
        assertThat(entry.latePosting(open).memo()).isEqualTo("late posting for 2026-11-25");
        assertThat(withMemo.latePosting(open).memo()).isEqualTo("Bill R1-000001; late posting for 2026-11-25");
        assertThat(entry.latePosting(open).lines()).isEqualTo(entry.lines());
    }

    @Test
    void aLineIsAPositiveDebitOrAPositiveCredit() {
        AccountRef cash = AccountRef.of(SystemKey.CASH_DRAWER);
        BigDecimal one = BigDecimal.ONE;
        BigDecimal zero = BigDecimal.ZERO;

        assertThatThrownBy(() -> new JournalLine(cash, one, one, null, Dimensions.NONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JournalLine(cash, zero, zero, null, Dimensions.NONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JournalLine(cash, one.negate(), zero, null, Dimensions.NONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JournalLine(cash, zero, one.negate(), null, Dimensions.NONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new JournalLine(cash, zero, one, null, Dimensions.NONE).signed())
                .isEqualByComparingTo("-1");
    }

    @Test
    void dimensionsKnowWhenTheyAreEmpty() {
        assertThat(Dimensions.NONE.isEmpty()).isTrue();
        assertThat(Dimensions.location(UUID.randomUUID()).isEmpty()).isFalse();
        UUID variant = UUID.randomUUID();
        assertThat(Dimensions.NONE.withVariant(variant).variantId()).isEqualTo(variant);
    }
}
