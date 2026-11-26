package com.bmp.sales.adapter.in.web;

import com.bmp.sales.adapter.in.web.generated.api.CreditNotesApi;
import com.bmp.sales.adapter.in.web.generated.model.CreditNote;
import com.bmp.sales.adapter.in.web.generated.model.CreditNotePage;
import com.bmp.sales.application.SalesSettings;
import com.bmp.sales.application.query.SalesQueryService;
import com.bmp.web.pagination.PageQuery;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/** Credit notes (SAL-10). */
@RestController
class CreditNotesController implements CreditNotesApi {

    private final SalesQueryService queries;
    private final SalesSettings settings;
    private final SalesWebMapper mapper;

    CreditNotesController(SalesQueryService queries, SalesSettings settings, SalesWebMapper mapper) {
        this.queries = queries;
        this.settings = settings;
        this.mapper = mapper;
    }

    @Override
    @PreAuthorize("hasPermission(null, 'sales:read')")
    public ResponseEntity<CreditNotePage> listCreditNotes(
            @Nullable UUID billId, Integer limit, @Nullable String cursor) {
        return ResponseEntity.ok(
                mapper.creditNotePage(queries.creditNotes(billId, PageQuery.of(limit, cursor)), settings.currency()));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'sales:read')")
    public ResponseEntity<CreditNote> getCreditNote(UUID creditNoteId) {
        return ResponseEntity.ok(mapper.creditNote(queries.creditNote(creditNoteId), settings.currency()));
    }
}
