package com.bmp.finance.domain.port;

import com.bmp.finance.domain.model.AccountType;
import com.bmp.finance.domain.model.Dimensions;
import com.bmp.finance.domain.model.PartyRef;
import com.bmp.finance.domain.model.Period;
import com.bmp.finance.domain.model.SourceType;
import com.bmp.finance.domain.model.SystemKey;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Read models of the journals: lists, account totals and POS clearing per bill. */
public interface LedgerReader {

    /** What a list of journals is filtered by. */
    record Filter(
            @Nullable Period period,
            @Nullable SourceType sourceType,
            @Nullable UUID sourceId,
            @Nullable UUID accountId,
            @Nullable LocalDate from,
            @Nullable LocalDate to) {}

    /** A position in the list: entry date descending, then id descending. */
    record Cursor(LocalDate entryDate, UUID id) {}

    /** A journal with its lines and accounts. */
    record JournalView(
            UUID id,
            String number,
            LocalDate entryDate,
            Period period,
            SourceType sourceType,
            UUID sourceId,
            @Nullable UUID sourceEventId,
            @Nullable String memo,
            @Nullable UUID reversesId,
            @Nullable UUID reversedById,
            String currency,
            Instant postedAt,
            @Nullable UUID postedBy,
            List<LineView> lines) {

        public JournalView {
            lines = List.copyOf(lines);
        }
    }

    /** A line with its account. */
    record LineView(
            UUID id,
            UUID accountId,
            String accountCode,
            @Nullable SystemKey systemKey,
            BigDecimal debit,
            BigDecimal credit,
            @Nullable PartyRef party,
            Dimensions dimensions) {}

    List<JournalView> page(Filter filter, @Nullable Cursor after, int limit);

    Optional<JournalView> view(UUID journalId);

    /** What an account moved: Σ debit and Σ credit. */
    record AccountTotal(
            UUID accountId,
            String code,
            String name,
            AccountType type,
            @Nullable SystemKey systemKey,
            BigDecimal debit,
            BigDecimal credit) {

        public BigDecimal balance() {
            return debit.subtract(credit);
        }
    }

    /** Every account with lines dated up to {@code asOf}, by code. */
    List<AccountTotal> totalsUpTo(LocalDate asOf);

    /** Every account with lines in the periods, by code; YEAR_END_CLOSE journals left out when asked. */
    List<AccountTotal> totalsOf(Period from, Period to, boolean withoutYearEndClose);

    /** A bill whose POS clearing does not net to zero, and since when its first line is dated. */
    record ClearingDifference(UUID billId, BigDecimal amount, LocalDate since) {}

    /** Bills with POS clearing lines in the period whose clearing, up to its last day, is not zero (FIN-06). */
    List<ClearingDifference> posClearingDifferences(Period period);

    /** Bills whose POS clearing is not zero and whose first line is dated before {@code before} (REP-07). */
    List<ClearingDifference> posClearingDifferencesBefore(LocalDate before);

    /** Whether any journal is dated in the period. */
    boolean hasJournals(Period period);

    /** When the last journal was posted (freshness of reports, REP-03). */
    Optional<Instant> lastPostedAt();
}
