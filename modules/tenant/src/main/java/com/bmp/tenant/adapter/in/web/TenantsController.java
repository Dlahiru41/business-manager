package com.bmp.tenant.adapter.in.web;

import com.bmp.shared.id.TenantId;
import com.bmp.tenant.adapter.in.web.generated.api.TenantsApi;
import com.bmp.tenant.adapter.in.web.generated.model.CreateTenantRequest;
import com.bmp.tenant.adapter.in.web.generated.model.Tenant;
import com.bmp.tenant.adapter.in.web.generated.model.TenantStatusChangeRequest;
import com.bmp.tenant.api.dto.NewTenant;
import com.bmp.tenant.application.command.CreateTenantHandler;
import com.bmp.tenant.application.command.TenantProfileHandlers;
import com.bmp.tenant.application.command.TenantProfileHandlers.StatusAction;
import com.bmp.tenant.application.query.TenantView;
import com.bmp.web.etag.Etags;
import com.bmp.web.idempotency.Idempotent;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/** Platform administration of tenants. */
@RestController
class TenantsController implements TenantsApi {

    private final CreateTenantHandler createTenant;
    private final TenantProfileHandlers profiles;
    private final TenantWebMapper mapper;

    TenantsController(CreateTenantHandler createTenant, TenantProfileHandlers profiles, TenantWebMapper mapper) {
        this.createTenant = createTenant;
        this.profiles = profiles;
        this.mapper = mapper;
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'platform:tenants')")
    public ResponseEntity<Tenant> createTenant(UUID idempotencyKey, CreateTenantRequest request) {
        TenantView created = createTenant.handle(new NewTenant(
                request.getName(),
                request.getLegalName(),
                request.getBusinessType(),
                request.getCurrency(),
                request.getTimeZone(),
                request.getBusinessDayCutoffHour(),
                request.getPlan(),
                null));
        return ResponseEntity.created(URI.create("/api/v1/tenants/" + created.id()))
                .eTag(Etags.of(created.version()))
                .body(mapper.toDto(created));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'platform:tenants')")
    public ResponseEntity<Tenant> suspendTenant(UUID tenantId, UUID idempotencyKey, TenantStatusChangeRequest request) {
        return changed(profiles.changeStatus(TenantId.of(tenantId), StatusAction.SUSPEND, request.getReason()));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'platform:tenants')")
    public ResponseEntity<Tenant> reactivateTenant(
            UUID tenantId, UUID idempotencyKey, TenantStatusChangeRequest request) {
        return changed(profiles.changeStatus(TenantId.of(tenantId), StatusAction.REACTIVATE, request.getReason()));
    }

    private ResponseEntity<Tenant> changed(TenantView view) {
        return ResponseEntity.ok().eTag(Etags.of(view.version())).body(mapper.toDto(view));
    }
}
