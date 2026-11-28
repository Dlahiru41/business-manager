package com.bmp.finance.adapter.in.web;

import com.bmp.finance.adapter.in.web.generated.api.JournalsApi;
import com.bmp.finance.adapter.in.web.generated.api.LedgerApi;
import com.bmp.finance.adapter.in.web.generated.model.JournalEntry;
import com.bmp.finance.adapter.in.web.generated.model.ListJournals200Response;
import com.bmp.finance.adapter.in.web.generated.model.ManualJournalLine;
import com.bmp.finance.adapter.in.web.generated.model.ManualJournalRequest;
import com.bmp.finance.adapter.in.web.generated.model.ProfitAndLoss;
import com.bmp.finance.adapter.in.web.generated.model.ReverseJournalRequest;
import com.bmp.finance.adapter.in.web.generated.model.SourceType;
import com.bmp.finance.adapter.in.web.generated.model.TrialBalance;
import com.bmp.finance.application.command.JournalHandlers;
import com.bmp.finance.application.query.FinanceQueries;
import com.bmp.finance.domain.model.Dimensions;
import com.bmp.finance.domain.model.PartyRef;
import com.bmp.finance.domain.model.PartyType;
import com.bmp.finance.domain.model.Period;
import com.bmp.web.idempotency.Idempotent;
import com.bmp.web.pagination.PageQuery;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

@RestController
class JournalsController implements JournalsApi, LedgerApi {

    private final JournalHandlers handlers;
    private final FinanceQueries queries;
    private final FinanceWebMapper mapper;

    JournalsController(JournalHandlers handlers, FinanceQueries queries, FinanceWebMapper mapper) {
        this.handlers = handlers;
        this.queries = queries;
        this.mapper = mapper;
    }

    @Override
    @PreAuthorize("hasPermission(null, 'finance:read')")
    public ResponseEntity<ListJournals200Response> listJournals(
            @Nullable String period,
            @Nullable SourceType sourceType,
            @Nullable UUID sourceId,
            @Nullable UUID accountId,
            @Nullable LocalDate from,
            @Nullable LocalDate to,
            Integer limit,
            @Nullable String cursor) {
        var page = queries.journals(
                new FinanceQueries.JournalSearch(
                        period == null ? null : Period.parse(period),
                        sourceType == null ? null : com.bmp.finance.domain.model.SourceType.valueOf(sourceType.name()),
                        sourceId,
                        accountId,
                        from,
                        to),
                PageQuery.of(limit, cursor));
        return ResponseEntity.ok(new ListJournals200Response()
                .items(page.items().stream().map(mapper::journal).toList())
                .page(FinanceWebMapper.page(page.page())));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'finance:journal')")
    public ResponseEntity<JournalEntry> createJournal(UUID idempotencyKey, ManualJournalRequest r) {
        var posted = handlers.manual(new JournalHandlers.ManualJournal(
                r.getId(),
                r.getEntryDate(),
                r.getMemo(),
                r.getLines().stream().map(JournalsController::line).toList()));
        return ResponseEntity.created(URI.create("/api/v1/journals/" + posted.id()))
                .body(mapper.journal(queries.journal(posted.id())));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'finance:read')")
    public ResponseEntity<JournalEntry> getJournal(UUID journalId) {
        return ResponseEntity.ok(mapper.journal(queries.journal(journalId)));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'finance:journal')")
    public ResponseEntity<JournalEntry> reverseJournal(UUID journalId, UUID idempotencyKey, ReverseJournalRequest r) {
        var posted = handlers.reverse(
                new JournalHandlers.ReverseJournal(r.getId(), journalId, r.getMemo(), r.getEntryDate()));
        return ResponseEntity.created(URI.create("/api/v1/journals/" + posted.id()))
                .body(mapper.journal(queries.journal(posted.id())));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'finance:read')")
    public ResponseEntity<TrialBalance> getTrialBalance(@Nullable LocalDate asOf, @Nullable String period) {
        return ResponseEntity.ok(
                mapper.trialBalance(queries.trialBalance(asOf, period == null ? null : Period.parse(period))));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'finance:read')")
    public ResponseEntity<ProfitAndLoss> getProfitAndLoss(String from, String to) {
        return ResponseEntity.ok(mapper.profitAndLoss(queries.profitAndLoss(Period.parse(from), Period.parse(to))));
    }

    private static JournalHandlers.ManualLine line(ManualJournalLine l) {
        var d = l.getDimensions();
        return new JournalHandlers.ManualLine(
                l.getAccountId(),
                l.getDebit() == null ? null : new BigDecimal(l.getDebit()),
                l.getCredit() == null ? null : new BigDecimal(l.getCredit()),
                l.getPartyType() == null || l.getPartyId() == null
                        ? null
                        : new PartyRef(PartyType.valueOf(l.getPartyType().name()), l.getPartyId()),
                d == null
                        ? Dimensions.NONE
                        : new Dimensions(
                                d.getVariantId(), d.getProductId(), d.getChannel(), d.getLocationId(), d.getBillId()));
    }
}
