package com.bmp.finance.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A journal as it was posted, with its accounts resolved; immutable (FIN-04). */
public record PostedJournal(
        UUID id,
        String number,
        LocalDate entryDate,
        Period period,
        SourceType sourceType,
        UUID sourceId,
        @Nullable UUID reversesId,
        List<Line> lines) {

    public PostedJournal {
        lines = List.copyOf(lines);
    }

    /** A posted line. */
    public record Line(
            UUID accountId,
            BigDecimal debit,
            BigDecimal credit,
            @Nullable PartyRef party,
            Dimensions dimensions) {}

    /**
     * Rule FIN-04: the exact reversal, every line's debit and credit swapped, pointing back at this journal; used for
     * voids (posting-rules.md §2) and manual reversals.
     */
    public JournalEntry reversal(
            SourceType type, UUID sourceId, @Nullable UUID sourceEventId, LocalDate date, @Nullable String memo) {
        JournalEntry.Builder builder = JournalEntry.builder(type, sourceId, sourceEventId, date)
                .memo(memo)
                .reverses(id);
        for (Line line : lines) {
            builder.line(new JournalLine(
                    AccountRef.of(line.accountId()),
                    /* debit= */ line.credit(),
                    /* credit= */ line.debit(),
                    line.party(),
                    line.dimensions()));
        }
        return builder.build();
    }
}
