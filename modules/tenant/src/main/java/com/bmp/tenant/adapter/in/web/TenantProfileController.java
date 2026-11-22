package com.bmp.tenant.adapter.in.web;

import com.bmp.tenant.adapter.in.web.generated.api.TenantProfileApi;
import com.bmp.tenant.adapter.in.web.generated.model.Tenant;
import com.bmp.tenant.adapter.in.web.generated.model.UpdateTenantRequest;
import com.bmp.tenant.application.command.TenantProfileHandlers;
import com.bmp.tenant.application.query.TenantQueryService;
import com.bmp.tenant.application.query.TenantView;
import com.bmp.web.etag.Etags;
import com.bmp.web.etag.IfMatch;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own tenant. */
@RestController
class TenantProfileController implements TenantProfileApi {

    private final TenantQueryService queries;
    private final TenantProfileHandlers profiles;
    private final TenantWebMapper mapper;

    TenantProfileController(TenantQueryService queries, TenantProfileHandlers profiles, TenantWebMapper mapper) {
        this.queries = queries;
        this.profiles = profiles;
        this.mapper = mapper;
    }

    @Override
    @PreAuthorize("hasPermission(null, 'tenant:read')")
    public ResponseEntity<Tenant> getCurrentTenant() {
        return respond(queries.currentTenant());
    }

    @Override
    @PreAuthorize("hasPermission(null, 'tenant:write')")
    public ResponseEntity<Tenant> updateCurrentTenant(UpdateTenantRequest request, @Nullable String ifMatch) {
        return respond(profiles.updateProfile(new TenantProfileHandlers.UpdateProfile(
                IfMatch.expectedVersion(ifMatch),
                request.getName(),
                request.getLegalName(),
                request.getBusinessType())));
    }

    private ResponseEntity<Tenant> respond(TenantView view) {
        return ResponseEntity.ok().eTag(Etags.of(view.version())).body(mapper.toDto(view));
    }
}
