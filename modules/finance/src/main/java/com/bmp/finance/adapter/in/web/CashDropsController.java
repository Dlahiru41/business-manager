package com.bmp.finance.adapter.in.web;

import com.bmp.finance.adapter.in.web.generated.api.CashDropsApi;
import com.bmp.finance.adapter.in.web.generated.model.CashDrop;
import com.bmp.finance.adapter.in.web.generated.model.CashDropRequest;
import com.bmp.finance.adapter.in.web.generated.model.ListCashDrops200Response;
import com.bmp.finance.application.command.CashDropHandler;
import com.bmp.finance.application.query.FinanceQueries;
import com.bmp.web.idempotency.Idempotent;
import com.bmp.web.pagination.PageQuery;
import com.bmp.web.pagination.PageResponse;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CashDropsController implements CashDropsApi {

    private final CashDropHandler handler;
    private final FinanceQueries queries;
    private final FinanceWebMapper mapper;

    CashDropsController(CashDropHandler handler, FinanceQueries queries, FinanceWebMapper mapper) {
        this.handler = handler;
        this.queries = queries;
        this.mapper = mapper;
    }

    @Override
    @PreAuthorize("hasPermission(null, 'finance:read')")
    public ResponseEntity<ListCashDrops200Response> listCashDrops(
            @Nullable UUID drawerSessionId, Integer limit, @Nullable String cursor) {
        PageQuery query = PageQuery.of(limit, cursor);
        var page = PageResponse.of(
                queries.cashDrops(drawerSessionId, Cursors.id(query.cursor()), query.fetchSize()),
                query,
                d -> List.of(d.id().toString()));
        return ResponseEntity.ok(new ListCashDrops200Response()
                .items(page.items().stream().map(mapper::cashDrop).toList())
                .page(FinanceWebMapper.page(page.page())));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'finance:cash-drop')")
    public ResponseEntity<CashDrop> recordCashDrop(UUID idempotencyKey, CashDropRequest r) {
        var drop = handler.drop(new CashDropHandler.Drop(
                r.getId(),
                r.getDrawerSessionId(),
                new BigDecimal(r.getAmount()),
                r.getReference(),
                r.getDroppedAt() == null ? null : r.getDroppedAt().toInstant()));
        return ResponseEntity.created(URI.create("/api/v1/cash-drops/" + drop.id()))
                .body(mapper.cashDrop(drop));
    }
}
