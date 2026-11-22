package com.bmp.iam.domain.model;

import com.bmp.iam.domain.error.IamErrorCode;
import com.bmp.shared.audit.Auditable;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.FieldError;
import com.bmp.shared.error.ValidationFailure;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import com.bmp.shared.security.PermissionPattern;
import com.bmp.shared.security.SystemRole;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A named set of permission grants in one tenant (aggregate root). System roles are seeded for every tenant from
 * {@link SystemRole} and can never be removed or deactivated (IAM-02); tenants may edit their permissions, except the
 * owner role, which always holds every tenant permission. Custom roles are created by the tenant.
 */
@Entity
@Table(schema = "iam", name = "role")
public class Role implements Auditable {

    /** Upper bound of grants per role, matching the API contract. */
    public static final int MAX_PERMISSIONS = 200;

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,39}");
    private static final String PLATFORM_AREA_PREFIX = "platform:";

    @Id
    private UUID id;

    @org.hibernate.annotations.TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, updatable = false)
    private boolean system;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RoleStatus status;

    @ElementCollection
    @CollectionTable(schema = "iam", name = "role_permission", joinColumns = @JoinColumn(name = "role_id"))
    @Column(name = "permission", nullable = false)
    private Set<String> permissions = new HashSet<>();

    /** Null until first stored, so Spring Data persists (not merges) new aggregates with assigned ids. */
    @Version
    private @Nullable Long version;

    @Embedded
    private AuditStamps audit = new AuditStamps();

    @SuppressWarnings("NullAway.Init") // JPA
    protected Role() {}

    private Role(RoleId id, TenantId tenant, String code, String name, boolean system, Set<String> permissions) {
        this.id = id.value();
        this.tenantId = tenant.value();
        this.code = code;
        this.name = Objects.requireNonNull(name, "name");
        this.system = system;
        this.status = RoleStatus.ACTIVE;
        this.permissions.addAll(validated(permissions));
    }

    /** The tenant's copy of a system role with its documented default permissions. */
    public static Role seed(RoleId id, TenantId tenant, SystemRole role) {
        if (!role.isTenantRole()) {
            throw new IllegalArgumentException(role + " is not a tenant role");
        }
        return new Role(id, tenant, role.code(), displayName(role), true, role.defaultPermissions());
    }

    /**
     * A custom role. Its code must not be a system role code, so that seeding can never collide with it.
     *
     * @param code upper-case code carried in tokens, e.g. {@code SUPERVISOR}; immutable
     */
    public static Role custom(RoleId id, TenantId tenant, String code, String name, Set<String> permissions) {
        if (!CODE.matcher(code).matches()) {
            throw new ValidationFailure(List.of(new FieldError("code", "PATTERN", "must match " + CODE.pattern())));
        }
        if (SystemRole.fromCode(code).isPresent()) {
            throw new BusinessRuleViolation(IamErrorCode.ROLE_CODE_TAKEN, Map.of("code", code));
        }
        return new Role(id, tenant, code, name, false, permissions);
    }

    public void rename(String newName) {
        this.name = Objects.requireNonNull(newName, "newName");
    }

    /** Replaces the grants. The owner role always holds every tenant permission (IAM-02). */
    public void changePermissions(Set<String> newPermissions) {
        if (isOwner()) {
            throw new BusinessRuleViolation(IamErrorCode.SYSTEM_ROLE_IMMUTABLE, Map.of("code", code));
        }
        Set<String> valid = validated(newPermissions);
        permissions.retainAll(valid);
        permissions.addAll(valid);
    }

    /** Rule IAM-02: system roles cannot be removed, and deactivating would remove them in effect. */
    public void deactivate() {
        if (system) {
            throw new BusinessRuleViolation(IamErrorCode.SYSTEM_ROLE_IMMUTABLE, Map.of("code", code));
        }
        this.status = RoleStatus.INACTIVE;
    }

    public void activate() {
        this.status = RoleStatus.ACTIVE;
    }

    /** The grants this role gives its holders now: none while inactive. */
    public Set<String> effectiveGrants() {
        return isActive() ? Set.copyOf(permissions) : Set.of();
    }

    public boolean isActive() {
        return status == RoleStatus.ACTIVE;
    }

    public boolean isOwner() {
        return system && SystemRole.OWNER.code().equals(code);
    }

    @Override
    public void recordCreation(Instant at, @Nullable UserId by) {
        audit.created(at, by);
    }

    @Override
    public void recordModification(Instant at, @Nullable UserId by) {
        audit.modified(at, by);
    }

    public RoleId id() {
        return RoleId.of(id);
    }

    public String code() {
        return code;
    }

    public String name() {
        return name;
    }

    public boolean isSystem() {
        return system;
    }

    public RoleStatus status() {
        return status;
    }

    public Set<String> permissions() {
        return Set.copyOf(permissions);
    }

    public long version() {
        return version != null ? version : 0;
    }

    private static Set<String> validated(Set<String> grants) {
        if (grants.size() > MAX_PERMISSIONS) {
            throw new BusinessRuleViolation(
                    IamErrorCode.INVALID_PERMISSION,
                    "A role holds at most " + MAX_PERMISSIONS + " grants",
                    Map.of("count", grants.size()));
        }
        for (String grant : grants) {
            // Platform permissions belong to platform administrators only; a tenant role can never hold them.
            if (!PermissionPattern.isValidGrant(grant) || grant.startsWith(PLATFORM_AREA_PREFIX)) {
                throw new BusinessRuleViolation(IamErrorCode.INVALID_PERMISSION, Map.of("permission", grant));
            }
        }
        return Set.copyOf(grants);
    }

    private static String displayName(SystemRole role) {
        String lower = role.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
