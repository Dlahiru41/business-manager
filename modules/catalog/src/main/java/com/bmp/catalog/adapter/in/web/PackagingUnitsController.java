package com.bmp.catalog.adapter.in.web;

import com.bmp.catalog.adapter.in.web.generated.api.PackagingUnitsApi;
import com.bmp.catalog.adapter.in.web.generated.model.AddBarcodeRequest;
import com.bmp.catalog.adapter.in.web.generated.model.NewPackagingUnit;
import com.bmp.catalog.adapter.in.web.generated.model.PackagingUnit;
import com.bmp.catalog.adapter.in.web.generated.model.PackagingUnitList;
import com.bmp.catalog.adapter.in.web.generated.model.UpdatePackagingUnitRequest;
import com.bmp.catalog.application.command.PackagingUnitHandlers;
import com.bmp.catalog.application.query.CatalogQueryService;
import com.bmp.catalog.application.query.PackagingUnitView;
import com.bmp.web.etag.Etags;
import com.bmp.web.etag.IfMatch;
import com.bmp.web.idempotency.Idempotent;
import java.net.URI;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/** Packaging units and barcodes; their ETag is the version of their variant. */
@RestController
class PackagingUnitsController implements PackagingUnitsApi {

    private final CatalogQueryService queries;
    private final PackagingUnitHandlers handlers;
    private final CatalogWebMapper mapper;

    PackagingUnitsController(CatalogQueryService queries, PackagingUnitHandlers handlers, CatalogWebMapper mapper) {
        this.queries = queries;
        this.handlers = handlers;
        this.mapper = mapper;
    }

    @Override
    @PreAuthorize("hasPermission(null, 'catalog:read')")
    public ResponseEntity<PackagingUnitList> listPackagingUnits(UUID variantId) {
        return ResponseEntity.ok(new PackagingUnitList()
                .items(queries.packagingUnits(variantId).stream()
                        .map(mapper::toDto)
                        .toList()));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'catalog:write')")
    public ResponseEntity<PackagingUnit> createPackagingUnit(
            UUID variantId, UUID idempotencyKey, NewPackagingUnit request) {
        PackagingUnitHandlers.UnitResult created = handlers.add(variantId, CatalogRequests.unit(request));
        return ResponseEntity.created(URI.create("/api/v1/variants/" + variantId + "/packaging-units/"
                        + created.unit().id()))
                .eTag(Etags.of(created.variantVersion()))
                .body(mapper.toDto(created.unit()));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'catalog:read')")
    public ResponseEntity<PackagingUnit> getPackagingUnit(UUID variantId, UUID unitId) {
        CatalogQueryService.UnitResult found = queries.packagingUnit(variantId, unitId);
        return respond(found.unit(), found.variantVersion());
    }

    @Override
    @PreAuthorize("hasPermission(null, 'catalog:write')")
    public ResponseEntity<PackagingUnit> updatePackagingUnit(
            UUID variantId, UUID unitId, UpdatePackagingUnitRequest request, @Nullable String ifMatch) {
        PackagingUnitHandlers.UnitResult changed = handlers.update(
                variantId, unitId, IfMatch.expectedVersion(ifMatch), CatalogRequests.updateUnit(request));
        return respond(changed.unit(), changed.variantVersion());
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'catalog:write')")
    public ResponseEntity<PackagingUnit> addBarcode(UUID unitId, UUID idempotencyKey, AddBarcodeRequest request) {
        PackagingUnitHandlers.UnitResult changed = handlers.addBarcode(unitId, request.getCode());
        return ResponseEntity.created(
                        URI.create("/api/v1/packaging-units/" + unitId + "/barcodes/" + request.getCode()))
                .eTag(Etags.of(changed.variantVersion()))
                .body(mapper.toDto(changed.unit()));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'catalog:write')")
    public ResponseEntity<Void> removeBarcode(UUID unitId, String code) {
        handlers.removeBarcode(unitId, code);
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<PackagingUnit> respond(PackagingUnitView view, long variantVersion) {
        return ResponseEntity.ok().eTag(Etags.of(variantVersion)).body(mapper.toDto(view));
    }
}
