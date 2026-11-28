package com.bmp.finance.api;

import com.bmp.finance.api.dto.AgingView;
import com.bmp.finance.api.dto.PosClearingDifference;
import com.bmp.finance.api.dto.ProfitAndLossView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * Read access to the ledger for reporting (REP-02, REP-04, REP-06, REP-07): reporting reads accounting here and never
 * recomputes it. Every call is in the current tenant.
 */
public interface LedgerQueries {

    /** Profit over the periods {@code from} to {@code to}, year-end closing entries left out (posting-rules.md §4). */
    ProfitAndLossView profitAndLoss(YearMonth from, YearMonth to);

    /** Every customer's or supplier's open amounts in the FIN-07 aging buckets. */
    AgingView aging(String partyType, LocalDate asOf);

    /** Σ of what customers owe past its due date on {@code asOf}. */
    BigDecimal overdueReceivables(LocalDate asOf);

    /** Bills whose POS clearing is not zero, first posted before {@code before} (REP-07). */
    List<PosClearingDifference> posClearingNotZero(LocalDate before);

    /** When the last journal was posted, for the freshness of reports (REP-03). */
    Optional<Instant> lastPostedAt();
}
