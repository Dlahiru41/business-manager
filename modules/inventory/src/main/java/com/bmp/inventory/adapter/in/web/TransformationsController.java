package com.bmp.inventory.adapter.in.web;

import com.bmp.inventory.adapter.in.web.generated.api.TransformationsApi;
import com.bmp.inventory.adapter.in.web.generated.model.TransformationRequest;
import com.bmp.inventory.adapter.in.web.generated.model.TransformationRun;
import com.bmp.inventory.adapter.in.web.generated.model.TransformationRunPage;
import com.bmp.inventory.application.command.TransformationHandler;
import com.bmp.inventory.application.query.InventoryQueryService;
import com.bmp.inventory.domain.service.TransformationPlanner;
import com.bmp.web.idempotency.Idempotent;
import com.bmp.web.pagination.PageQuery;
import java.math.BigDecimal;
import java.net.URI;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/** Transformation runs (INV-09, INV-10). */
@RestController
class TransformationsController implements TransformationsApi {

    private final TransformationHandler handler;
    private final InventoryQueryService queries;
    private final InventoryWebMapper mapper;

    TransformationsController(TransformationHandler handler, InventoryQueryService queries, InventoryWebMapper mapper) {
        this.handler = handler;
        this.queries = queries;
        this.mapper = mapper;
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'inventory:transform')")
    public ResponseEntity<TransformationRun> runTransformation(UUID idempotencyKey, TransformationRequest request) {
        var run = handler.run(new TransformationHandler.RunCommand(
                request.getDefinitionId(),
                request.getLocationId(),
                InventoryRequests.decimal(request.getRuns()),
                request.getOutputs() == null
                        ? null
                        : request.getOutputs().stream()
                                .map(o -> new TransformationPlanner.Actual(
                                        o.getVariantId(), o.getStockState(), new BigDecimal(o.getQtyBase())))
                                .toList(),
                request.getNote(),
                InventoryRequests.instant(request.getOccurredAt())));
        return ResponseEntity.created(URI.create("/api/v1/stock/transformations/" + run.id()))
                .body(mapper.run(run));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'inventory:read')")
    public ResponseEntity<TransformationRunPage> listTransformationRuns(
            @Nullable UUID definitionId, @Nullable UUID locationId, @Nullable Integer limit, @Nullable String cursor) {
        var page = queries.runs(definitionId, locationId, PageQuery.of(limit, cursor));
        return ResponseEntity.ok(new TransformationRunPage()
                .items(page.items().stream().map(mapper::run).toList())
                .page(mapper.page(page)));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'inventory:read')")
    public ResponseEntity<TransformationRun> getTransformationRun(UUID runId) {
        return ResponseEntity.ok(mapper.run(queries.run(runId)));
    }
}
