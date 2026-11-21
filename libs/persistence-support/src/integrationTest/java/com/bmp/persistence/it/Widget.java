package com.bmp.persistence.it;

import com.bmp.shared.audit.Auditable;
import com.bmp.shared.id.UserId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

@Entity
@Table(schema = "sample", name = "widget")
public class Widget implements Auditable {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private @Nullable UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private @Nullable UUID updatedBy;

    protected Widget() {}

    public Widget(UUID id, String name) {
        this.id = id;
        this.name = name;
    }

    public void rename(String newName) {
        this.name = newName;
    }

    @Override
    public void recordCreation(Instant at, @Nullable UserId by) {
        this.createdAt = at;
        this.updatedAt = at;
        this.createdBy = by != null ? by.value() : null;
        this.updatedBy = this.createdBy;
    }

    @Override
    public void recordModification(Instant at, @Nullable UserId by) {
        this.updatedAt = at;
        this.updatedBy = by != null ? by.value() : null;
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public String name() {
        return name;
    }

    public long version() {
        return version;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public @Nullable UUID createdBy() {
        return createdBy;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
