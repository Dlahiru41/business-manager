package com.bmp.tenant.adapter.in.web;

import com.bmp.tenant.adapter.in.web.generated.api.ReceiptTemplatesApi;
import com.bmp.tenant.adapter.in.web.generated.model.CreateReceiptTemplateRequest;
import com.bmp.tenant.adapter.in.web.generated.model.LocationPagePage;
import com.bmp.tenant.adapter.in.web.generated.model.ReceiptTemplate;
import com.bmp.tenant.adapter.in.web.generated.model.ReceiptTemplatePage;
import com.bmp.tenant.adapter.in.web.generated.model.UpdateReceiptTemplateRequest;
import com.bmp.tenant.application.command.ReceiptTemplateHandlers;
import com.bmp.tenant.application.query.ReceiptTemplateView;
import com.bmp.tenant.application.query.TenantQueryService;
import com.bmp.web.etag.Etags;
import com.bmp.web.etag.IfMatch;
import com.bmp.web.idempotency.Idempotent;
import com.bmp.web.pagination.PageQuery;
import com.bmp.web.pagination.PageResponse;
import java.net.URI;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/** Receipt templates of the caller's tenant. */
@RestController
class ReceiptTemplatesController implements ReceiptTemplatesApi {

    private final TenantQueryService queries;
    private final ReceiptTemplateHandlers handlers;
    private final TenantWebMapper mapper;
    private final JsonDocuments documents;

    ReceiptTemplatesController(
            TenantQueryService queries,
            ReceiptTemplateHandlers handlers,
            TenantWebMapper mapper,
            JsonDocuments documents) {
        this.queries = queries;
        this.handlers = handlers;
        this.mapper = mapper;
        this.documents = documents;
    }

    @Override
    @PreAuthorize("hasPermission(null, 'tenant:write')")
    public ResponseEntity<ReceiptTemplatePage> listReceiptTemplates(@Nullable Integer limit, @Nullable String cursor) {
        PageResponse<ReceiptTemplateView> page = queries.receiptTemplates(PageQuery.of(limit, cursor));
        return ResponseEntity.ok(new ReceiptTemplatePage()
                .items(page.items().stream().map(mapper::toDto).toList())
                .page(new LocationPagePage()
                        .limit(page.page().limit())
                        .nextCursor(page.page().nextCursor())
                        .hasMore(page.page().hasMore())));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'tenant:write')")
    public ResponseEntity<ReceiptTemplate> createReceiptTemplate(
            UUID idempotencyKey, CreateReceiptTemplateRequest request) {
        var layout = new com.bmp.tenant.domain.model.ReceiptTemplate.Layout(
                request.getName(),
                request.getPaperWidthMm().getValue(),
                objectOrEmpty(documents.fromMap(request.getHeader())),
                objectOrEmpty(documents.fromMap(request.getFooter())),
                objectOrEmpty(documents.fromMap(request.getFields())),
                request.getLanguage().getValue());
        ReceiptTemplateView created = handlers.create(
                new ReceiptTemplateHandlers.CreateTemplate(layout, Boolean.TRUE.equals(request.getIsDefault())));
        return ResponseEntity.created(URI.create("/api/v1/receipt-templates/" + created.id()))
                .eTag(Etags.of(created.version()))
                .body(mapper.toDto(created));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'tenant:write')")
    public ResponseEntity<ReceiptTemplate> getReceiptTemplate(UUID templateId) {
        return respond(queries.receiptTemplate(templateId));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'tenant:write')")
    public ResponseEntity<ReceiptTemplate> updateReceiptTemplate(
            UUID templateId, UpdateReceiptTemplateRequest request, @Nullable String ifMatch) {
        return respond(handlers.update(new ReceiptTemplateHandlers.UpdateTemplate(
                templateId,
                IfMatch.expectedVersion(ifMatch),
                request.getName(),
                request.getPaperWidthMm() != null ? request.getPaperWidthMm().getValue() : null,
                documents.fromMap(request.getHeader()),
                documents.fromMap(request.getFooter()),
                documents.fromMap(request.getFields()),
                request.getLanguage() != null ? request.getLanguage().getValue() : null,
                request.getIsDefault())));
    }

    private static String objectOrEmpty(@Nullable String json) {
        return json != null ? json : "{}";
    }

    private ResponseEntity<ReceiptTemplate> respond(ReceiptTemplateView view) {
        return ResponseEntity.ok().eTag(Etags.of(view.version())).body(mapper.toDto(view));
    }
}
