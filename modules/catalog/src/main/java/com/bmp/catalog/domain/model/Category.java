package com.bmp.catalog.domain.model;

import com.bmp.catalog.domain.error.CatalogErrorCode;
import com.bmp.shared.audit.Auditable;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A product category in a tree. The materialized {@code path} holds the ids from the root down to this category
 * ({@code root/child/this}), so a subtree is a path prefix and ancestors need no recursive query. Names may change
 * without rewriting paths.
 */
@Entity
@Table(schema = "catalog", name = "category")
public class Category implements Auditable {

    /** Levels below the root a category may sit at (1 = top level). */
    public static final int MAX_DEPTH = 5;

    static final String SEPARATOR = "/";

    @Id
    private UUID id;

    @org.hibernate.annotations.TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "parent_id")
    private @Nullable UUID parentId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String path;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CatalogStatus status;

    /** Null until first stored, so Spring Data persists (not merges) new aggregates with assigned ids. */
    @Version
    private @Nullable Long version;

    @Embedded
    private AuditStamps audit = new AuditStamps();

    @SuppressWarnings("NullAway.Init") // JPA
    protected Category() {}

    private Category(UUID id, TenantId tenant, String name, @Nullable Category parent) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = tenant.value();
        this.name = Objects.requireNonNull(name, "name");
        this.status = CatalogStatus.ACTIVE;
        placeUnder(parent, 0);
    }

    public static Category create(UUID id, TenantId tenant, String name, @Nullable Category parent) {
        return new Category(id, tenant, name, parent);
    }

    public void rename(String newName) {
        this.name = Objects.requireNonNull(newName, "newName");
    }

    /**
     * Moves this category (with its subtree) under another one, or to the top level.
     *
     * @param subtreeHeight levels of descendants below this category (0 without children)
     * @return the old path, so the store can rewrite the descendants' paths
     */
    public String moveTo(@Nullable Category newParent, int subtreeHeight) {
        if (newParent != null && newParent.pathIds().contains(id)) {
            throw new BusinessRuleViolation(
                    CatalogErrorCode.CATEGORY_CYCLE, Map.of("categoryId", id, "parentId", newParent.id));
        }
        String oldPath = path;
        placeUnder(newParent, subtreeHeight);
        return oldPath;
    }

    public void deactivate() {
        this.status = CatalogStatus.INACTIVE;
    }

    public void activate() {
        this.status = CatalogStatus.ACTIVE;
    }

    /** Ids from the root down to the parent. */
    public List<UUID> ancestorIds() {
        List<UUID> ids = pathIds();
        return ids.subList(0, ids.size() - 1);
    }

    /** Levels below the root: 1 for a top-level category. */
    public int depth() {
        return pathIds().size();
    }

    @Override
    public void recordCreation(Instant at, @Nullable UserId by) {
        audit.created(at, by);
    }

    @Override
    public void recordModification(Instant at, @Nullable UserId by) {
        audit.modified(at, by);
    }

    public UUID id() {
        return id;
    }

    public @Nullable UUID parentId() {
        return parentId;
    }

    public String name() {
        return name;
    }

    public String path() {
        return path;
    }

    public CatalogStatus status() {
        return status;
    }

    public long version() {
        return version != null ? version : 0;
    }

    public Instant createdAt() {
        return audit.createdAt();
    }

    private void placeUnder(@Nullable Category parent, int subtreeHeight) {
        int depth = (parent != null ? parent.depth() : 0) + 1;
        if (depth + subtreeHeight > MAX_DEPTH) {
            throw new BusinessRuleViolation(
                    CatalogErrorCode.CATEGORY_TOO_DEEP,
                    "Categories nest at most " + MAX_DEPTH + " levels",
                    Map.of("maxDepth", MAX_DEPTH));
        }
        this.parentId = parent != null ? parent.id : null;
        this.path = parent != null ? parent.path + SEPARATOR + id : id.toString();
    }

    private List<UUID> pathIds() {
        return Arrays.stream(path.split(SEPARATOR)).map(UUID::fromString).toList();
    }
}
