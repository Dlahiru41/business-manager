package com.bmp.iam.adapter.in.web;

import com.bmp.iam.adapter.in.web.generated.api.RolesApi;
import com.bmp.iam.adapter.in.web.generated.model.CreateRoleRequest;
import com.bmp.iam.adapter.in.web.generated.model.Role;
import com.bmp.iam.adapter.in.web.generated.model.RoleList;
import com.bmp.iam.adapter.in.web.generated.model.UpdateRoleRequest;
import com.bmp.iam.application.command.CreateRoleHandler;
import com.bmp.iam.application.command.UpdateRoleHandler;
import com.bmp.iam.application.query.IamQueryService;
import com.bmp.iam.application.query.RoleView;
import com.bmp.iam.domain.model.RoleStatus;
import com.bmp.web.etag.Etags;
import com.bmp.web.etag.IfMatch;
import com.bmp.web.idempotency.Idempotent;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/** Roles of the caller's tenant and their permission grants. */
@RestController
class RolesController implements RolesApi {

    private final IamQueryService queries;
    private final CreateRoleHandler createRole;
    private final UpdateRoleHandler updateRole;
    private final IamWebMapper mapper;

    RolesController(
            IamQueryService queries, CreateRoleHandler createRole, UpdateRoleHandler updateRole, IamWebMapper mapper) {
        this.queries = queries;
        this.createRole = createRole;
        this.updateRole = updateRole;
        this.mapper = mapper;
    }

    @Override
    @PreAuthorize("hasPermission(null, 'iam:roles')")
    public ResponseEntity<RoleList> listRoles() {
        return ResponseEntity.ok(
                new RoleList().items(queries.roles().stream().map(mapper::toDto).toList()));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'iam:roles')")
    public ResponseEntity<Role> createRole(UUID idempotencyKey, CreateRoleRequest request) {
        RoleView created = createRole.handle(new CreateRoleHandler.CreateRole(
                request.getCode(), request.getName(), Set.copyOf(request.getPermissions())));
        return ResponseEntity.created(URI.create("/api/v1/roles/" + created.id()))
                .eTag(Etags.of(created.version()))
                .body(mapper.toDto(created));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'iam:roles')")
    public ResponseEntity<Role> getRole(UUID roleId) {
        return respond(queries.role(roleId));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'iam:roles')")
    public ResponseEntity<Role> updateRole(UUID roleId, UpdateRoleRequest request, @Nullable String ifMatch) {
        List<String> permissions = request.getPermissions();
        return respond(updateRole.handle(new UpdateRoleHandler.UpdateRole(
                roleId,
                IfMatch.expectedVersion(ifMatch),
                request.getName(),
                permissions != null ? Set.copyOf(permissions) : null,
                request.getStatus() != null
                        ? RoleStatus.valueOf(request.getStatus().getValue())
                        : null)));
    }

    private ResponseEntity<Role> respond(RoleView view) {
        return ResponseEntity.ok().eTag(Etags.of(view.version())).body(mapper.toDto(view));
    }
}
