package com.bmp.finance.domain.model;

import java.math.BigDecimal;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One line of a journal: a debit or a credit, never both, never negative.
 *
 * @param party the customer or supplier whose account the line moves, if any
 */
public record JournalLine(
        AccountRef account,
        BigDecimal debit,
        BigDecimal credit,
        @Nullable PartyRef party,
        Dimensions dimensions) {

    public JournalLine {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(debit, "debit");
        Objects.requireNonNull(credit, "credit");
        Objects.requireNonNull(dimensions, "dimensions");
        if (debit.signum() < 0 || credit.signum() < 0 || (debit.signum() > 0) == (credit.signum() > 0)) {
            throw new IllegalArgumentException("A line is a positive debit or a positive credit");
        }
    }

    /** Debit − credit. */
    public BigDecimal signed() {
        return debit.subtract(credit);
    }
}
