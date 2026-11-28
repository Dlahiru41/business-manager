package com.bmp.finance.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PostedJournalTest {

    @Test
    void fin04AReversalSwapsEveryLineAndPointsBackAtTheOriginal() {
        UUID id = UUID.randomUUID();
        UUID ar = UUID.randomUUID();
        UUID sales = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        Dimensions dims = Dimensions.bill(UUID.randomUUID(), null, null);
        PostedJournal sale = new PostedJournal(
                id,
                "JE-000007",
                LocalDate.parse("2026-11-25"),
                Period.parse("2026-11"),
                SourceType.SALE,
                UUID.randomUUID(),
                null,
                List.of(
                        new PostedJournal.Line(
                                ar, new BigDecimal("1550.00"), BigDecimal.ZERO, PartyRef.customer(customer), dims),
                        new PostedJournal.Line(sales, BigDecimal.ZERO, new BigDecimal("1550.00"), null, dims)));
        UUID event = UUID.randomUUID();
        UUID bill = UUID.randomUUID();

        JournalEntry reversal = sale.reversal(SourceType.SALE_VOID, bill, event, LocalDate.parse("2026-11-26"), "Void");

        assertThat(reversal.reversesId()).isEqualTo(id);
        assertThat(reversal.sourceType()).isEqualTo(SourceType.SALE_VOID);
        assertThat(reversal.sourceId()).isEqualTo(bill);
        assertThat(reversal.sourceEventId()).isEqualTo(event);
        assertThat(reversal.memo()).isEqualTo("Void");
        assertThat(reversal.lines()).hasSize(2);
        assertThat(reversal.lines().get(0).account()).isEqualTo(AccountRef.of(ar));
        assertThat(reversal.lines().get(0).credit()).isEqualByComparingTo("1550.00");
        assertThat(reversal.lines().get(0).party()).isEqualTo(PartyRef.customer(customer));
        assertThat(reversal.lines().get(0).dimensions()).isEqualTo(dims);
        assertThat(reversal.lines().get(1).debit()).isEqualByComparingTo("1550.00");
    }
}
