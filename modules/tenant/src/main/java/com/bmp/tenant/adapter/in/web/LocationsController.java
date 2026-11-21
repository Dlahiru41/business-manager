package com.bmp.tenant.adapter.in.web;

import com.bmp.tenant.adapter.in.web.generated.api.LocationsApi;
import com.bmp.tenant.adapter.in.web.generated.model.CreateLocationRequest;
import com.bmp.tenant.adapter.in.web.generated.model.Location;
import com.bmp.tenant.adapter.in.web.generated.model.LocationPage;
import com.bmp.tenant.adapter.in.web.generated.model.LocationPagePage;
import com.bmp.tenant.adapter.in.web.generated.model.UpdateLocationRequest;
import com.bmp.tenant.application.command.LocationHandlers;
import com.bmp.tenant.application.query.LocationView;
import com.bmp.tenant.application.query.TenantQueryService;
import com.bmp.tenant.domain.model.LocationStatus;
import com.bmp.tenant.domain.model.LocationType;
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

/** Locations of the caller's tenant. */
@RestController
class LocationsController implements LocationsApi {

    private final TenantQueryService queries;
    private final LocationHandlers handlers;
    private final TenantWebMapper mapper;
    private final JsonDocuments documents;

    LocationsController(
            TenantQueryService queries, LocationHandlers handlers, TenantWebMapper mapper, JsonDocuments documents) {
        this.queries = queries;
        this.handlers = handlers;
        this.mapper = mapper;
        this.documents = documents;
    }

    @Override
    @PreAuthorize("hasPermission(null, 'tenant:read')")
    public ResponseEntity<LocationPage> listLocations(@Nullable Integer limit, @Nullable String cursor) {
        PageResponse<LocationView> page = queries.locations(PageQuery.of(limit, cursor));
        LocationPage body = new LocationPage()
                .items(page.items().stream().map(mapper::toDto).toList())
                .page(new LocationPagePage()
                        .limit(page.page().limit())
                        .nextCursor(page.page().nextCursor())
                        .hasMore(page.page().hasMore()));
        return ResponseEntity.ok(body);
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'tenant:write')")
    public ResponseEntity<Location> createLocation(UUID idempotencyKey, CreateLocationRequest request) {
        LocationView created = handlers.create(new LocationHandlers.CreateLocation(
                request.getName(),
                LocationType.valueOf(request.getType().getValue()),
                documents.fromAddress(request.getAddress())));
        return ResponseEntity.created(URI.create("/api/v1/locations/" + created.id()))
                .eTag(Etags.of(created.version()))
                .body(mapper.toDto(created));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'tenant:read')")
    public ResponseEntity<Location> getLocation(UUID locationId) {
        return respond(queries.location(locationId));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'tenant:write')")
    public ResponseEntity<Location> updateLocation(
            UUID locationId, UpdateLocationRequest request, @Nullable String ifMatch) {
        return respond(handlers.update(new LocationHandlers.UpdateLocation(
                locationId,
                IfMatch.expectedVersion(ifMatch),
                request.getName(),
                request.getType() != null
                        ? LocationType.valueOf(request.getType().getValue())
                        : null,
                request.getAddress() != null,
                documents.fromAddress(request.getAddress()),
                request.getStatus() != null
                        ? LocationStatus.valueOf(request.getStatus().getValue())
                        : null)));
    }

    private ResponseEntity<Location> respond(LocationView view) {
        return ResponseEntity.ok().eTag(Etags.of(view.version())).body(mapper.toDto(view));
    }
}
