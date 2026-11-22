package com.bmp.catalog.adapter.in.web;

import com.bmp.catalog.adapter.in.web.generated.api.TransformationDefinitionsApi;
import com.bmp.catalog.adapter.in.web.generated.model.CreateTransformationDefinitionRequest;
import com.bmp.catalog.adapter.in.web.generated.model.DefinitionStatus;
import com.bmp.catalog.adapter.in.web.generated.model.TransformationDefinition;
import com.bmp.catalog.adapter.in.web.generated.model.TransformationDefinitionPage;
import com.bmp.catalog.adapter.in.web.generated.model.TransformationKind;
import com.bmp.catalog.adapter.in.web.generated.model.UpdateTransformationDefinitionRequest;
import com.bmp.catalog.application.command.DefinitionHandlers;
import com.bmp.catalog.application.query.CatalogQueryService;
import com.bmp.catalog.application.query.DefinitionView;
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

/** Transformation definitions (CAT-10, CAT-11). */
@RestController
class TransformationDefinitionsController implements TransformationDefinitionsApi {

    private final CatalogQueryService queries;
    private final DefinitionHandlers handlers;
    private final CatalogWebMapper mapper;

    TransformationDefinitionsController(
            CatalogQueryService queries, DefinitionHandlers handlers, CatalogWebMapper mapper) {
        this.queries = queries;
        this.handlers = handlers;
        this.mapper = mapper;
    }

    @Override
    @PreAuthorize("hasPermission(null, 'catalog:write')")
    public ResponseEntity<TransformationDefinitionPage> listTransformationDefinitions(
            @Nullable TransformationKind kind,
            @Nullable DefinitionStatus status,
            @Nullable UUID variantId,
            @Nullable Integer limit,
            @Nullable String cursor) {
        PageResponse<DefinitionView> page = queries.definitions(
                kind != null ? com.bmp.catalog.domain.model.TransformationKind.valueOf(kind.getValue()) : null,
                status != null ? com.bmp.catalog.domain.model.DefinitionStatus.valueOf(status.getValue()) : null,
                variantId,
                PageQuery.of(limit, cursor));
        return ResponseEntity.ok(new TransformationDefinitionPage()
                .items(page.items().stream().map(mapper::toDto).toList())
                .page(UnitsController.Pages.of(page)));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'catalog:write')")
    public ResponseEntity<TransformationDefinition> createTransformationDefinition(
            UUID idempotencyKey, CreateTransformationDefinitionRequest request) {
        DefinitionView created = handlers.create(new DefinitionHandlers.CreateDefinition(
                request.getCode(),
                request.getName(),
                com.bmp.catalog.domain.model.TransformationKind.valueOf(
                        request.getKind().getValue()),
                CatalogRequests.decimal(request.getExpectedYieldPct()),
                CatalogRequests.lines(request)));
        return ResponseEntity.created(URI.create("/api/v1/transformation-definitions/" + created.id()))
                .eTag(Etags.of(created.version()))
                .body(mapper.toDto(created));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'catalog:write')")
    public ResponseEntity<TransformationDefinition> getTransformationDefinition(UUID definitionId) {
        return respond(queries.definition(definitionId));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'catalog:write')")
    public ResponseEntity<TransformationDefinition> updateTransformationDefinition(
            UUID definitionId, UpdateTransformationDefinitionRequest request, @Nullable String ifMatch) {
        return respond(handlers.update(new DefinitionHandlers.UpdateDefinition(
                definitionId,
                IfMatch.expectedVersion(ifMatch),
                request.getName(),
                CatalogRequests.decimal(request.getExpectedYieldPct()),
                request.getLines() != null ? CatalogRequests.lines(request.getLines()) : null)));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'catalog:write')")
    public ResponseEntity<TransformationDefinition> publishTransformationDefinition(
            UUID definitionId, UUID idempotencyKey) {
        return respond(handlers.publish(definitionId));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'catalog:write')")
    public ResponseEntity<TransformationDefinition> retireTransformationDefinition(
            UUID definitionId, UUID idempotencyKey) {
        return respond(handlers.retire(definitionId));
    }

    private ResponseEntity<TransformationDefinition> respond(DefinitionView view) {
        return ResponseEntity.ok().eTag(Etags.of(view.version())).body(mapper.toDto(view));
    }
}
