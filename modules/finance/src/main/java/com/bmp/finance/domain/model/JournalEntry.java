package com.bmp.finance.domain.model;

import com.bmp.finance.domain.error.FinanceErrorCode;
import com.bmp.shared.error.TechnicalException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A journal entry ready to be posted: balanced by construction (FIN-03), since the only way to get one is {@link
 * Builder#build()}, which refuses Σ debit ≠ Σ credit with {@code LEDGER_IMBALANCE}. Posted journals never change
 * (FIN-04); corrections are new entries that point back with {@code reversesId}.
 *
 * @param sourceEventId the event it was posted from, unique per tenant (FIN-02); empty for commands
 */
public record JournalEntry(
        SourceType sourceType,
        UUID sourceId,
        @Nullable UUID sourceEventId,
        LocalDate entryDate,
        @Nullable String memo,
        @Nullable UUID reversesId,
        List<JournalLine> lines) {

    /** Money is kept at four decimals, as stock costs are (06 §3). */
    public static final int SCALE = 4;

    public JournalEntry {
        lines = List.copyOf(lines);
    }

    public static Builder builder(SourceType type, UUID sourceId, @Nullable UUID sourceEventId, LocalDate date) {
        return new Builder(type, sourceId, sourceEventId, date);
    }

    public BigDecimal totalDebit() {
        return lines.stream().map(JournalLine::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal totalCredit() {
        return lines.stream().map(JournalLine::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Rule FIN-05: the same entry entered on the first day of an open period because its own was closed, with the memo
     * {@code late posting for <original date>}.
     */
    public JournalEntry latePosting(LocalDate openDay) {
        String note = "late posting for " + entryDate;
        return new JournalEntry(
                sourceType,
                sourceId,
                sourceEventId,
                openDay,
                memo == null ? note : memo + "; " + note,
                reversesId,
                lines);
    }

    /** Collects lines and checks the balance when built. */
    public static final class Builder {

        private final SourceType type;
        private final UUID sourceId;
        private final @Nullable UUID sourceEventId;
        private final LocalDate date;
        private @Nullable String memo;
        private @Nullable UUID reversesId;
        private final List<JournalLine> lines = new ArrayList<>();

        private Builder(SourceType type, UUID sourceId, @Nullable UUID sourceEventId, LocalDate date) {
            this.type = Objects.requireNonNull(type, "type");
            this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
            this.sourceEventId = sourceEventId;
            this.date = Objects.requireNonNull(date, "date");
        }

        public Builder memo(@Nullable String text) {
            this.memo = text;
            return this;
        }

        public Builder reverses(@Nullable UUID journalId) {
            this.reversesId = journalId;
            return this;
        }

        /** Debits {@code amount}; a negative amount credits its absolute value, zero adds nothing. */
        public Builder debit(AccountRef account, BigDecimal amount, @Nullable PartyRef party, Dimensions dimensions) {
            return line(account, amount, party, dimensions);
        }

        public Builder debit(AccountRef account, BigDecimal amount) {
            return debit(account, amount, null, Dimensions.NONE);
        }

        public Builder debit(SystemKey key, BigDecimal amount, @Nullable PartyRef party, Dimensions dimensions) {
            return debit(AccountRef.of(key), amount, party, dimensions);
        }

        public Builder debit(SystemKey key, BigDecimal amount, Dimensions dimensions) {
            return debit(AccountRef.of(key), amount, null, dimensions);
        }

        /** Credits {@code amount}; a negative amount debits its absolute value, zero adds nothing. */
        public Builder credit(AccountRef account, BigDecimal amount, @Nullable PartyRef party, Dimensions dimensions) {
            return line(account, amount.negate(), party, dimensions);
        }

        public Builder credit(AccountRef account, BigDecimal amount) {
            return credit(account, amount, null, Dimensions.NONE);
        }

        public Builder credit(SystemKey key, BigDecimal amount, @Nullable PartyRef party, Dimensions dimensions) {
            return credit(AccountRef.of(key), amount, party, dimensions);
        }

        public Builder credit(SystemKey key, BigDecimal amount, Dimensions dimensions) {
            return credit(AccountRef.of(key), amount, null, dimensions);
        }

        /** Adds a line as it is (manual journals, reversals). */
        public Builder line(JournalLine line) {
            lines.add(line);
            return this;
        }

        private Builder line(AccountRef account, BigDecimal signed, @Nullable PartyRef party, Dimensions dims) {
            BigDecimal amount = signed.setScale(SCALE, RoundingMode.HALF_UP);
            if (amount.signum() > 0) {
                lines.add(new JournalLine(account, amount, BigDecimal.ZERO.setScale(SCALE), party, dims));
            } else if (amount.signum() < 0) {
                lines.add(new JournalLine(account, BigDecimal.ZERO.setScale(SCALE), amount.negate(), party, dims));
            }
            return this;
        }

        public boolean isEmpty() {
            return lines.isEmpty();
        }

        /**
         * Rule FIN-03: the entry, or {@code LEDGER_IMBALANCE} when Σ debit ≠ Σ credit.
         *
         * @throws TechnicalException with {@link FinanceErrorCode#LEDGER_IMBALANCE}
         */
        public JournalEntry build() {
            JournalEntry entry = new JournalEntry(type, sourceId, sourceEventId, date, memo, reversesId, lines);
            if (entry.totalDebit().compareTo(entry.totalCredit()) != 0) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("sourceType", type.name());
                details.put("sourceId", sourceId);
                details.put("debit", entry.totalDebit().toPlainString());
                details.put("credit", entry.totalCredit().toPlainString());
                throw new TechnicalException(FinanceErrorCode.LEDGER_IMBALANCE, details, null);
            }
            return entry;
        }

        /** The entry, or empty when nothing was left to post (every amount zero). */
        public Optional<JournalEntry> buildIfAny() {
            return lines.isEmpty() ? Optional.empty() : Optional.of(build());
        }
    }
}
