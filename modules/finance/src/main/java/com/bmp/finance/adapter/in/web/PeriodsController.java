package com.bmp.finance.adapter.in.web;

import com.bmp.finance.adapter.in.web.generated.api.PeriodsApi;
import com.bmp.finance.adapter.in.web.generated.model.CloseChecks;
import com.bmp.finance.adapter.in.web.generated.model.ClosePeriod202Response;
import com.bmp.finance.adapter.in.web.generated.model.ClosePeriodRequest;
import com.bmp.finance.adapter.in.web.generated.model.FiscalPeriod;
import com.bmp.finance.adapter.in.web.generated.model.ListPeriods200Response;
import com.bmp.finance.application.command.PeriodCloseHandlers;
import com.bmp.finance.application.query.FinanceQueries;
import com.bmp.finance.domain.model.Period;
import com.bmp.web.idempotency.Idempotent;
import com.bmp.web.pagination.PageQuery;
import com.bmp.web.pagination.PageResponse;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PeriodsController implements PeriodsApi {

    private final PeriodCloseHandlers close;
    private final com.bmp.finance.application.query.CloseChecks checks;
    private final FinanceQueries queries;
    private final FinanceWebMapper mapper;

    PeriodsController(
            PeriodCloseHandlers close,
            com.bmp.finance.application.query.CloseChecks checks,
            FinanceQueries queries,
            FinanceWebMapper mapper) {
        this.close = close;
        this.checks = checks;
        this.queries = queries;
        this.mapper = mapper;
    }

    @Override
    @PreAuthorize("hasPermission(null, 'finance:read')")
    public ResponseEntity<ListPeriods200Response> listPeriods(Integer limit, @Nullable String cursor) {
        PageQuery query = PageQuery.of(limit, cursor);
        String before = Cursors.first(query.cursor());
        var page = PageResponse.of(
                queries.periods(before == null ? null : Period.parse(before), query.fetchSize()),
                query,
                p -> List.of(p.period().toString()));
        int start = queries.fiscalYearStartMonth();
        return ResponseEntity.ok(new ListPeriods200Response()
                .items(page.items().stream().map(p -> mapper.period(p, start)).toList())
                .page(FinanceWebMapper.page(page.page())));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'finance:read')")
    public ResponseEntity<FiscalPeriod> getPeriod(String period) {
        return ResponseEntity.ok(mapper.period(queries.period(Period.parse(period)), queries.fiscalYearStartMonth()));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'finance:read')")
    public ResponseEntity<CloseChecks> getCloseChecks(String period) {
        Period p = Period.parse(period);
        return ResponseEntity.ok(mapper.closeChecks(p, checks.of(p, List.of()), queries.currency()));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'finance:close-period')")
    public ResponseEntity<ClosePeriod202Response> closePeriod(
            String period, UUID idempotencyKey, ClosePeriodRequest r) {
        List<UUID> acknowledged = r.getAcknowledgedBillIds() == null ? List.of() : r.getAcknowledgedBillIds();
        var operation = close.request(period, acknowledged);
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/operations/" + operation.id()))
                .body(mapper.operation(operation));
    }
}
