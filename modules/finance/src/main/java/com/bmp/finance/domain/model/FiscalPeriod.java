package com.bmp.finance.domain.model;

import com.bmp.finance.domain.error.FinanceErrorCode;
import com.bmp.shared.error.BusinessRuleViolation;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A period and its status (FIN-05, FIN-06): OPEN → CLOSING → CLOSED, never back. */
public record FiscalPeriod(
        Period period,
        PeriodStatus status,
        @Nullable Instant closedAt,
        @Nullable UUID closedBy) {

    public FiscalPeriod {
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(status, "status");
    }

    /** A period nothing was done to yet. */
    public static FiscalPeriod open(Period period) {
        return new FiscalPeriod(period, PeriodStatus.OPEN, null, null);
    }

    /** Rule FIN-05: only an OPEN period takes postings. */
    public boolean acceptsPostings() {
        return status == PeriodStatus.OPEN;
    }

    /**
     * Rule FIN-06: a close may start once the period has ended (its last day before {@code today}) and it is neither
     * closed nor closing.
     */
    public FiscalPeriod startClosing(LocalDate today) {
        if (status == PeriodStatus.CLOSED) {
            throw refused(FinanceErrorCode.PERIOD_ALREADY_CLOSED);
        }
        if (status == PeriodStatus.CLOSING) {
            throw refused(FinanceErrorCode.PERIOD_CLOSE_IN_PROGRESS);
        }
        if (!period.lastDay().isBefore(today)) {
            throw new BusinessRuleViolation(
                    FinanceErrorCode.PERIOD_NOT_ENDED,
                    Map.of(
                            "period",
                            period.toString(),
                            "lastDay",
                            period.lastDay().toString()));
        }
        return new FiscalPeriod(period, PeriodStatus.CLOSING, null, null);
    }

    /** The close succeeded. */
    public FiscalPeriod closed(Instant at, @Nullable UUID by) {
        if (status != PeriodStatus.CLOSING) {
            throw refused(FinanceErrorCode.PERIOD_ALREADY_CLOSED);
        }
        return new FiscalPeriod(period, PeriodStatus.CLOSED, at, by);
    }

    /** The close failed its checks: the period is open again. */
    public FiscalPeriod reopened() {
        return status == PeriodStatus.CLOSING ? open(period) : this;
    }

    private BusinessRuleViolation refused(FinanceErrorCode code) {
        return new BusinessRuleViolation(code, Map.of("period", period.toString(), "status", status.name()));
    }
}
