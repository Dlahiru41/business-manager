package com.bmp.iam.adapter.in.web;

import com.bmp.iam.adapter.in.web.generated.api.MeApi;
import com.bmp.iam.adapter.in.web.generated.model.Me;
import com.bmp.iam.adapter.in.web.generated.model.Permissions;
import com.bmp.iam.application.query.IamQueryService;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.shared.id.DeviceId;
import com.bmp.shared.id.TenantId;
import com.bmp.web.security.AccessPolicy;
import com.bmp.web.security.BmpPrincipal;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/** Who is calling and what they may do; the identity always comes from the access token. */
@RestController
class MeController implements MeApi {

    private final IamQueryService queries;
    private final AccessPolicy access;

    MeController(IamQueryService queries, AccessPolicy access) {
        this.queries = queries;
        this.access = access;
    }

    @Override
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Me> getMe() {
        BmpPrincipal principal = principal();
        Me me = new Me()
                .userId(principal.userId().value())
                .roles(principal.roles().stream().sorted().toList())
                .platformAdmin(principal.isPlatformAdmin());
        DeviceId device = principal.deviceId();
        if (device != null) {
            me.deviceId(device.value());
        }
        Optional<TenantId> tenant = TenantContext.current();
        if (tenant.isPresent()) {
            me.tenantId(tenant.get().value());
            queries.profile(principal.userId())
                    .ifPresent(profile ->
                            me.displayName(profile.displayName()).defaultLocationId(profile.defaultLocationId()));
        }
        return ResponseEntity.ok(me);
    }

    @Override
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Permissions> getMyPermissions() {
        Permissions body = new Permissions()
                .permissions(access.grantsOf(principal()).stream().sorted().toList());
        return ResponseEntity.ok(body);
    }

    private static BmpPrincipal principal() {
        return AccessPolicy.currentPrincipal()
                .orElseThrow(() -> new BusinessRuleViolation(PlatformErrorCode.UNAUTHENTICATED));
    }
}
