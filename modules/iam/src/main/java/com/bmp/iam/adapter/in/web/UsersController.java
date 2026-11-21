package com.bmp.iam.adapter.in.web;

import com.bmp.iam.adapter.in.web.generated.api.UsersApi;
import com.bmp.iam.adapter.in.web.generated.model.CreateUserRequest;
import com.bmp.iam.adapter.in.web.generated.model.ReplaceRolesRequest;
import com.bmp.iam.adapter.in.web.generated.model.UpdateUserRequest;
import com.bmp.iam.adapter.in.web.generated.model.User;
import com.bmp.iam.adapter.in.web.generated.model.UserPage;
import com.bmp.iam.adapter.in.web.generated.model.UserPagePage;
import com.bmp.iam.application.command.CreateUserHandler;
import com.bmp.iam.application.command.ReplaceUserRolesHandler;
import com.bmp.iam.application.command.UpdateUserHandler;
import com.bmp.iam.application.query.IamQueryService;
import com.bmp.iam.application.query.UserView;
import com.bmp.iam.domain.model.UserStatus;
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

/** Users of the caller's tenant. */
@RestController
class UsersController implements UsersApi {

    private final IamQueryService queries;
    private final CreateUserHandler createUser;
    private final UpdateUserHandler updateUser;
    private final ReplaceUserRolesHandler replaceRoles;
    private final IamWebMapper mapper;

    UsersController(
            IamQueryService queries,
            CreateUserHandler createUser,
            UpdateUserHandler updateUser,
            ReplaceUserRolesHandler replaceRoles,
            IamWebMapper mapper) {
        this.queries = queries;
        this.createUser = createUser;
        this.updateUser = updateUser;
        this.replaceRoles = replaceRoles;
        this.mapper = mapper;
    }

    @Override
    @PreAuthorize("hasPermission(null, 'iam:users')")
    public ResponseEntity<UserPage> listUsers(@Nullable Integer limit, @Nullable String cursor) {
        PageResponse<UserView> page = queries.users(PageQuery.of(limit, cursor));
        UserPage body = new UserPage()
                .items(page.items().stream().map(mapper::toDto).toList())
                .page(new UserPagePage()
                        .limit(page.page().limit())
                        .nextCursor(page.page().nextCursor())
                        .hasMore(page.page().hasMore()));
        return ResponseEntity.ok(body);
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'iam:users')")
    public ResponseEntity<User> createUser(UUID idempotencyKey, CreateUserRequest request) {
        UserView created = createUser.handle(new CreateUserHandler.CreateUser(
                request.getUsername(),
                request.getEmail(),
                request.getDisplayName(),
                request.getDefaultLocationId(),
                mapper.toCommand(request.getRoles())));
        return ResponseEntity.created(URI.create("/api/v1/users/" + created.id()))
                .eTag(Etags.of(created.version()))
                .body(mapper.toDto(created));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'iam:users')")
    public ResponseEntity<User> getUser(UUID userId) {
        return respond(queries.user(userId));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'iam:users')")
    public ResponseEntity<User> updateUser(UUID userId, UpdateUserRequest request, @Nullable String ifMatch) {
        return respond(updateUser.handle(new UpdateUserHandler.UpdateUser(
                userId,
                IfMatch.expectedVersion(ifMatch),
                request.getDisplayName(),
                request.getStatus() != null
                        ? UserStatus.valueOf(request.getStatus().getValue())
                        : null,
                request.getDefaultLocationId())));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'iam:users')")
    public ResponseEntity<User> replaceUserRoles(UUID userId, ReplaceRolesRequest request, @Nullable String ifMatch) {
        return respond(replaceRoles.handle(new ReplaceUserRolesHandler.ReplaceUserRoles(
                userId, IfMatch.expectedVersion(ifMatch), mapper.toCommand(request.getRoles()))));
    }

    private ResponseEntity<User> respond(UserView view) {
        return ResponseEntity.ok().eTag(Etags.of(view.version())).body(mapper.toDto(view));
    }
}
