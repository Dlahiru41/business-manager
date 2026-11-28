package com.bmp.finance.domain.posting;

import com.bmp.finance.domain.model.AccountRef;
import com.bmp.finance.domain.model.Dimensions;
import com.bmp.finance.domain.model.JournalEntry;
import com.bmp.finance.domain.model.PaidFrom;
import com.bmp.finance.domain.model.PartyRef;
import com.bmp.finance.domain.model.SourceType;
import com.bmp.finance.domain.model.SystemKey;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** {@code ExpenseRecorded} (FIN-09): Dr the category's account · Cr CASH_DRAWER, BANK or AP (the supplier). */
public final class ExpenseRule implements PostingRule<ExpenseRule.Expense> {

    /** An expense. */
    public record Expense(
            UUID expenseId,
            UUID accountId,
            BigDecimal amount,
            PaidFrom paidFrom,
            @Nullable UUID supplierId,
            LocalDate expenseDate,
            String description) {}

    @Override
    public Optional<JournalEntry> post(Expense e) {
        JournalEntry.Builder builder = JournalEntry.builder(SourceType.EXPENSE, e.expenseId(), null, e.expenseDate())
                .memo(e.description())
                .debit(AccountRef.of(e.accountId()), e.amount());
        switch (e.paidFrom()) {
            case CASH_DRAWER -> builder.credit(SystemKey.CASH_DRAWER, e.amount(), Dimensions.NONE);
            case BANK -> builder.credit(SystemKey.BANK, e.amount(), Dimensions.NONE);
            case PAYABLE ->
                builder.credit(
                        SystemKey.AP,
                        e.amount(),
                        e.supplierId() == null ? null : PartyRef.supplier(e.supplierId()),
                        Dimensions.NONE);
        }
        return builder.buildIfAny();
    }
}
