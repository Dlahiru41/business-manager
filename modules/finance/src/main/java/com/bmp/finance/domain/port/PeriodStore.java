package com.bmp.finance.domain.port;

import com.bmp.finance.domain.model.FiscalPeriod;
import com.bmp.finance.domain.model.Period;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Fiscal periods of the current tenant; a period without a row is OPEN. */
public interface PeriodStore {

    /** Rule FIN-05: the period, held FOR SHARE so no close runs while the posting commits. */
    FiscalPeriod lockForPosting(Period period);

    /** Rule FIN-06: the period, held FOR UPDATE by a close; postings wait for it. */
    FiscalPeriod lockForClose(Period period);

    Optional<FiscalPeriod> find(Period period);

    void save(FiscalPeriod period);

    /** Periods with a row or with journals, newest first, before {@code before}. */
    List<FiscalPeriod> page(@Nullable Period before, int limit);
}
