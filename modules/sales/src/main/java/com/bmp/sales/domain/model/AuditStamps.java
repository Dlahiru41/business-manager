package com.bmp.sales.domain.model;

import com.bmp.shared.id.UserId;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Creation and modification stamps, set by the persistence layer through {@code Auditable}. */
@Embeddable
public class AuditStamps {

    @Column(name = "created_at", nullable = false, updatable = false)
    private @Nullable Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private @Nullable UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private @Nullable Instant updatedAt;

    @Column(name = "updated_by")
    private @Nullable UUID updatedBy;

    void created(Instant at, @Nullable UserId by) {
        this.createdAt = at;
        this.createdBy = by != null ? by.value() : null;
        modified(at, by);
    }

    void modified(Instant at, @Nullable UserId by) {
        this.updatedAt = at;
        this.updatedBy = by != null ? by.value() : null;
    }

    /** When the entity was first stored; {@link Instant#EPOCH} before that. */
    public Instant createdAt() {
        return createdAt != null ? createdAt : Instant.EPOCH;
    }

    public Instant updatedAt() {
        return updatedAt != null ? updatedAt : createdAt();
    }
}
