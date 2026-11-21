package com.bmp.audit.adapter.in.web;

import com.bmp.audit.adapter.in.web.generated.api.AuditApi;
import com.bmp.audit.adapter.in.web.generated.model.AuditRecordPage;
import com.bmp.audit.adapter.in.web.generated.model.AuditRecordPagePage;
import com.bmp.audit.application.query.AuditQueryService;
import com.bmp.audit.domain.model.AuditRecord;
import com.bmp.web.pagination.PageQuery;
import com.bmp.web.pagination.PageResponse;
import java.time.OffsetDateTime;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/** The audit trail of the caller's tenant. */
@RestController
class AuditRecordsController implements AuditApi {

    private final AuditQueryService queries;
    private final AuditWebMapper mapper;

    AuditRecordsController(AuditQueryService queries, AuditWebMapper mapper) {
        this.queries = queries;
        this.mapper = mapper;
    }

    @Override
    @PreAuthorize("hasPermission(null, 'audit:read')")
    public ResponseEntity<AuditRecordPage> listAuditRecords(
            @Nullable String resourceType,
            @Nullable String resourceId,
            @Nullable OffsetDateTime from,
            @Nullable OffsetDateTime to,
            @Nullable Integer limit,
            @Nullable String cursor) {
        PageResponse<AuditRecord> page = queries.find(
                resourceType,
                resourceId,
                from != null ? from.toInstant() : null,
                to != null ? to.toInstant() : null,
                PageQuery.of(limit, cursor));
        return ResponseEntity.ok(new AuditRecordPage()
                .items(page.items().stream().map(mapper::toDto).toList())
                .page(new AuditRecordPagePage()
                        .limit(page.page().limit())
                        .nextCursor(page.page().nextCursor())
                        .hasMore(page.page().hasMore())));
    }
}
