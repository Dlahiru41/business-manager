package com.bmp.finance.domain.posting;

import com.bmp.finance.domain.model.Dimensions;
import com.bmp.finance.domain.model.JournalEntry;
import com.bmp.finance.domain.model.SourceType;
import com.bmp.finance.domain.model.SystemKey;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** Cash drop to the bank: Dr BANK · Cr CASH_DRAWER. */
public final class CashDropRule implements PostingRule<CashDropRule.Drop> {

    /** Cash taken from a drawer to the bank. */
    public record Drop(UUID dropId, BigDecimal amount, LocalDate date) {}

    @Override
    public Optional<JournalEntry> post(Drop d) {
        return JournalEntry.builder(SourceType.CASH_DROP, d.dropId(), null, d.date())
                .memo("Cash drop to the bank")
                .debit(SystemKey.BANK, d.amount(), Dimensions.NONE)
                .credit(SystemKey.CASH_DRAWER, d.amount(), Dimensions.NONE)
                .buildIfAny();
    }
}
