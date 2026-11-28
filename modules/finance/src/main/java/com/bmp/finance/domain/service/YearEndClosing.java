package com.bmp.finance.domain.service;

import com.bmp.finance.domain.model.AccountRef;
import com.bmp.finance.domain.model.AccountType;
import com.bmp.finance.domain.model.Dimensions;
import com.bmp.finance.domain.model.JournalEntry;
import com.bmp.finance.domain.model.SourceType;
import com.bmp.finance.domain.model.SystemKey;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Rule FIN-06 (posting-rules.md §2, {@code PeriodClosed} at year end): every income and expense account is brought to
 * zero against retained earnings, on the last day of the fiscal year.
 */
public final class YearEndClosing {

    private YearEndClosing() {}

    /** An account's balance over the fiscal year: debit − credit. */
    public record Balance(UUID accountId, AccountType type, BigDecimal balance) {}

    public static Optional<JournalEntry> close(
            UUID closeId, LocalDate lastDay, String fiscalYear, List<Balance> balances) {
        JournalEntry.Builder builder = JournalEntry.builder(SourceType.YEAR_END_CLOSE, closeId, null, lastDay)
                .memo("Year-end close " + fiscalYear);
        BigDecimal result = BigDecimal.ZERO;
        for (Balance b : balances) {
            if (b.type().isProfitAndLoss() && b.balance().signum() != 0) {
                builder.credit(AccountRef.of(b.accountId()), b.balance());
                result = result.add(b.balance());
            }
        }
        builder.debit(SystemKey.RETAINED_EARNINGS, result, Dimensions.NONE);
        return builder.buildIfAny();
    }
}
