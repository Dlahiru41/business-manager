package com.bmp.finance.application.command;

import com.bmp.finance.application.FinanceEvents;
import com.bmp.finance.application.FinanceSettings;
import com.bmp.finance.application.JournalPoster;
import com.bmp.finance.application.PeriodCloseRequested;
import com.bmp.finance.application.query.CloseChecks;
import com.bmp.finance.domain.error.FinanceErrorCode;
import com.bmp.finance.domain.model.FiscalPeriod;
import com.bmp.finance.domain.model.Period;
import com.bmp.finance.domain.model.PeriodStatus;
import com.bmp.finance.domain.model.PostedJournal;
import com.bmp.finance.domain.port.LedgerReader;
import com.bmp.finance.domain.port.PeriodStore;
import com.bmp.finance.domain.service.YearEndClosing;
import com.bmp.persistence.operation.Operations;
import com.bmp.shared.context.ActorContext;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.id.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Rule FIN-06: a close is accepted (202), then runs from {@link PeriodCloseRequested}: the checks, the year-end close
 * when it is the last period of the fiscal year, CLOSED and {@code PeriodClosed}; a failed check reopens the period and
 * fails the operation with its code (ADR-032 §9).
 */
@Service
public class PeriodCloseHandlers {

    static final String OPERATION = "PERIOD_CLOSE";

    private static final Logger log = LoggerFactory.getLogger(PeriodCloseHandlers.class);

    private final PeriodStore periods;
    private final CloseChecks checks;
    private final LedgerReader reader;
    private final JournalPoster poster;
    private final Operations operations;
    private final FinanceEvents events;
    private final FinanceSettings settings;
    private final IdGenerator ids;
    private final Clock clock;
    private final JsonMapper json;

    public PeriodCloseHandlers(
            PeriodStore periods,
            CloseChecks checks,
            LedgerReader reader,
            JournalPoster poster,
            Operations operations,
            FinanceEvents events,
            FinanceSettings settings,
            IdGenerator ids,
            Clock clock,
            JsonMapper json) {
        this.periods = periods;
        this.checks = checks;
        this.reader = reader;
        this.poster = poster;
        this.operations = operations;
        this.events = events;
        this.settings = settings;
        this.ids = ids;
        this.clock = clock;
        this.json = json;
    }

    /** Accepts a close: the period is CLOSING until it runs; refuses what can be refused at once. */
    @Transactional
    public Operations.Operation request(String period, List<UUID> acknowledgedBillIds) {
        Period p = Period.parse(period);
        FiscalPeriod status = periods.lockForClose(p);
        Optional<Period> earlier = periods.earliestOpenWithJournalsBefore(p);
        if (earlier.isPresent()) {
            throw new BusinessRuleViolation(
                    FinanceErrorCode.EARLIER_PERIOD_OPEN,
                    Map.of("period", p.toString(), "earlier", earlier.get().toString()));
        }
        periods.save(status.startClosing(settings.today()));
        UUID operation = ids.newUuid();
        operations.create(operation, OPERATION);
        events.closeRequested(operation, p.toString(), acknowledgedBillIds);
        log.atInfo()
                .addKeyValue("period", p)
                .addKeyValue("operationId", operation)
                .log("Period close requested");
        return operations.find(operation).orElseThrow();
    }

    /** Runs the close of the event, once; a failed check is recorded on the operation, never thrown. */
    @Transactional
    public void run(PeriodCloseRequested e) {
        Optional<Operations.Operation> operation = operations.find(e.operationId());
        if (operation.isEmpty() || operation.get().isFinished()) {
            return;
        }
        operations.running(e.operationId(), 10);
        Period p = Period.parse(e.period());
        FiscalPeriod status = periods.lockForClose(p);
        if (status.status() != PeriodStatus.CLOSING) {
            fail(e, FinanceErrorCode.PERIOD_ALREADY_CLOSED, Map.of("period", p.toString()));
            return;
        }
        CloseChecks.Result result = checks.of(p, e.acknowledgedBillIds());
        if (!result.ready()) {
            periods.save(status.reopened());
            fail(e, result.blocking(), result.details());
            return;
        }
        boolean yearEnd = p.isYearEnd(settings.fiscalYearStartMonth());
        UUID closingJournal = null;
        if (yearEnd) {
            periods.save(FiscalPeriod.open(p));
            closingJournal =
                    yearEndClose(e.operationId(), p).map(PostedJournal::id).orElse(null);
        }
        Instant now = clock.instant();
        var user = ActorContext.currentOrSystem().userId();
        periods.save(status.closed(now, user == null ? null : user.value()));
        events.periodClosed(e.operationId(), p.toString(), yearEnd, closingJournal, now);
        operations.succeeded(e.operationId(), "/api/v1/periods/" + p);
        log.atInfo().addKeyValue("period", p).addKeyValue("yearEnd", yearEnd).log("Period closed");
    }

    private Optional<PostedJournal> yearEndClose(UUID operation, Period last) {
        Period first = last.fiscalYearStart(settings.fiscalYearStartMonth());
        List<YearEndClosing.Balance> balances = reader.totalsOf(first, last, true).stream()
                .map(t -> new YearEndClosing.Balance(t.accountId(), t.type(), t.balance()))
                .toList();
        return poster.post(() -> YearEndClosing.close(operation, last.lastDay(), first + " to " + last, balances));
    }

    private void fail(PeriodCloseRequested e, FinanceErrorCode code, Map<String, ?> details) {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put(
                "type",
                "https://errors.bmp.example/finance/"
                        + code.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
        problem.put("title", code.name());
        problem.put("status", 409);
        problem.put("code", code.name());
        problem.put("details", details);
        operations.failed(e.operationId(), json.writeValueAsString(problem));
        log.atInfo().addKeyValue("period", e.period()).addKeyValue("code", code).log("Period close refused");
    }
}
