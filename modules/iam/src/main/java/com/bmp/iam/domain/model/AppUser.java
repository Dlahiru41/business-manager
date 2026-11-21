package com.bmp.iam.domain.model;

import com.bmp.shared.audit.Auditable;
import com.bmp.shared.error.FieldError;
import com.bmp.shared.error.ValidationFailure;
import com.bmp.shared.id.LocationId;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The profile of a person who signs in to a tenant (aggregate root). Credentials live in the identity provider; the id
 * is the identity provider subject (ADR-014), so every actor id recorded by other modules names a user here.
 * Whether a change may remove the tenant's last owner (IAM-01) depends on other users and is checked by
 * {@code LastOwnerRule} in the handler.
 */
@Entity
@Table(schema = "iam", name = "app_user")
public class AppUser implements Auditable {

    /** Upper bound of roles per user, matching the API contract. */
    public static final int MAX_ROLES = 20;

    @Id
    private UUID id;

    @org.hibernate.annotations.TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "idp_subject", nullable = false)
    private UUID idpSubject;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status;

    @Column(name = "default_location_id")
    private @Nullable UUID defaultLocationId;

    /** Replaced as a whole on every change (delete and insert), which keeps the mapping simple and the table small. */
    @ElementCollection
    @CollectionTable(schema = "iam", name = "user_role", joinColumns = @JoinColumn(name = "user_id"))
    private List<RoleGrant> roles = new ArrayList<>();

    /** Null until first stored, so Spring Data persists (not merges) new aggregates with assigned ids. */
    @Version
    private @Nullable Long version;

    @Embedded
    private AuditStamps audit = new AuditStamps();

    @SuppressWarnings("NullAway.Init") // JPA
    protected AppUser() {}

    private AppUser(
            UserId subject,
            TenantId tenant,
            String displayName,
            @Nullable LocationId defaultLocation,
            List<RoleGrant> roles) {
        this.id = subject.value();
        this.tenantId = tenant.value();
        this.idpSubject = subject.value();
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.status = UserStatus.ACTIVE;
        this.defaultLocationId = defaultLocation != null ? defaultLocation.value() : null;
        this.roles.addAll(validated(roles));
    }

    /**
     * An active user linked to an identity provider account.
     *
     * @param subject the identity provider subject, which becomes the user id
     */
    public static AppUser create(
            UserId subject,
            TenantId tenant,
            String displayName,
            @Nullable LocationId defaultLocation,
            List<RoleGrant> roles) {
        return new AppUser(subject, tenant, displayName, defaultLocation, roles);
    }

    public void rename(String newDisplayName) {
        this.displayName = Objects.requireNonNull(newDisplayName, "newDisplayName");
    }

    public void changeDefaultLocation(LocationId location) {
        this.defaultLocationId = location.value();
    }

    public void disable() {
        this.status = UserStatus.DISABLED;
    }

    public void enable() {
        this.status = UserStatus.ACTIVE;
    }

    /** Replaces every role grant; each role may appear once. */
    public void replaceRoles(List<RoleGrant> newRoles) {
        List<RoleGrant> valid = validated(newRoles);
        roles.clear();
        roles.addAll(valid);
    }

    public boolean holds(RoleId role) {
        return roles.stream().anyMatch(grant -> grant.roleId().equals(role.value()));
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    @Override
    public void recordCreation(Instant at, @Nullable UserId by) {
        audit.created(at, by);
    }

    @Override
    public void recordModification(Instant at, @Nullable UserId by) {
        audit.modified(at, by);
    }

    public UserId id() {
        return UserId.of(id);
    }

    public UserId idpSubject() {
        return UserId.of(idpSubject);
    }

    public String displayName() {
        return displayName;
    }

    public UserStatus status() {
        return status;
    }

    public @Nullable UUID defaultLocationId() {
        return defaultLocationId;
    }

    public List<RoleGrant> roles() {
        return List.copyOf(roles);
    }

    public long version() {
        return version != null ? version : 0;
    }

    public Instant createdAt() {
        return audit.createdAt();
    }

    private static List<RoleGrant> validated(List<RoleGrant> grants) {
        if (grants.size() > MAX_ROLES) {
            throw new ValidationFailure(
                    List.of(new FieldError("roles", "SIZE", "a user holds at most " + MAX_ROLES + " roles")));
        }
        Set<UUID> seen = new HashSet<>();
        for (RoleGrant grant : grants) {
            if (!seen.add(grant.roleId())) {
                throw new ValidationFailure(List.of(new FieldError("roles", "DUPLICATE", "each role may appear once")));
            }
        }
        return List.copyOf(grants);
    }
}
