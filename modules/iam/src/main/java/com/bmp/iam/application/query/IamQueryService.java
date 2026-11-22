package com.bmp.iam.application.query;

import com.bmp.iam.domain.error.IamErrorCode;
import com.bmp.iam.domain.model.AppUser;
import com.bmp.iam.domain.model.Role;
import com.bmp.iam.domain.model.RoleGrant;
import com.bmp.iam.domain.port.RoleRepository;
import com.bmp.iam.domain.port.UserRepository;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.NotFoundException;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.shared.id.UserId;
import com.bmp.web.pagination.CursorCodec;
import com.bmp.web.pagination.PageQuery;
import com.bmp.web.pagination.PageResponse;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of the iam module for the current tenant; returns read models, never aggregates. */
@Service
@Transactional(readOnly = true)
public class IamQueryService {

    /** Start of the keyset for the first page: before any row can have been created. */
    private static final Instant FIRST_PAGE = Instant.parse("1970-01-01T00:00:00Z");

    private static final UUID NIL = new UUID(0L, 0L);
    private static final int CURSOR_VALUES = 2;

    private final UserRepository users;
    private final RoleRepository roles;

    public IamQueryService(UserRepository users, RoleRepository roles) {
        this.users = users;
        this.roles = roles;
    }

    public PageResponse<UserView> users(PageQuery query) {
        Keyset after = Keyset.of(query);
        List<AppUser> page = users.findPage(after.createdAt(), after.id(), query.fetchSize());
        Map<UUID, String> codes = roleCodes(page.stream()
                .flatMap(u -> u.roles().stream())
                .map(RoleGrant::roleId)
                .collect(Collectors.toSet()));
        List<UserView> views = page.stream().map(u -> UserView.of(u, codes)).toList();
        return PageResponse.of(views, query, v -> List.of(v.createdAt().toString(), v.id().toString()));
    }

    public UserView user(UUID id) {
        AppUser user = users.findById(id)
                .orElseThrow(() -> new NotFoundException(IamErrorCode.USER_NOT_FOUND, Map.of("userId", id)));
        return view(user);
    }

    /** The profile of the authenticated caller, if one exists in the current tenant. */
    public Optional<UserView> profile(UserId subject) {
        return users.findById(subject.value()).map(this::view);
    }

    public List<RoleView> roles() {
        return roles.findAllOrdered().stream().map(RoleView::of).toList();
    }

    public RoleView role(UUID id) {
        return roles.findById(id)
                .map(RoleView::of)
                .orElseThrow(() -> new NotFoundException(IamErrorCode.ROLE_NOT_FOUND, Map.of("roleId", id)));
    }

    private UserView view(AppUser user) {
        return UserView.of(
                user, roleCodes(user.roles().stream().map(RoleGrant::roleId).toList()));
    }

    private Map<UUID, String> roleCodes(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return roles.findByIdIn(ids).stream()
                .collect(Collectors.toMap(role -> role.id().value(), Role::code, (a, b) -> a));
    }

    /** Position after which the next page starts: (createdAt, id) of the previous page's last row. */
    private record Keyset(Instant createdAt, UUID id) {
        static Keyset of(PageQuery query) {
            String cursor = query.cursor();
            if (cursor == null) {
                return new Keyset(FIRST_PAGE, NIL);
            }
            List<String> values = CursorCodec.decode(cursor, CURSOR_VALUES);
            try {
                return new Keyset(Instant.parse(values.get(0)), UUID.fromString(values.get(1)));
            } catch (DateTimeParseException | IllegalArgumentException e) {
                throw new BusinessRuleViolation(
                        PlatformErrorCode.INVALID_PARAMETER, "The cursor is invalid", Map.of("parameter", "cursor"));
            }
        }
    }
}
