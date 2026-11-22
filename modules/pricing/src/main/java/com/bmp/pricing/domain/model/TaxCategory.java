package com.bmp.pricing.domain.model;

import com.bmp.shared.audit.Auditable;
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
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A tax category products refer to (VAT standard rate); its rates change over time as {@link TaxRate}s. */
@Entity
@Table(schema = "pricing", name = "tax_category")
public class TaxCategory implements Auditable {

    @Id
    private UUID id;

    @org.hibernate.annotations.TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PricingStatus status;

    /** Null until first stored, so Spring Data persists (not merges) new aggregates with assigned ids. */
    @Version
    private @Nullable Long version;

    @Embedded
    private AuditStamps audit = new AuditStamps();

    @SuppressWarnings("NullAway.Init") // JPA
    protected TaxCategory() {}

    private TaxCategory(UUID id, TenantId tenant, String code, String name) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = tenant.value();
        this.code = code.toUpperCase(Locale.ROOT);
        this.name = Objects.requireNonNull(name, "name");
        this.status = PricingStatus.ACTIVE;
    }

    public static TaxCategory create(UUID id, TenantId tenant, String code, String name) {
        return new TaxCategory(id, tenant, code, name);
    }

    public void rename(String newName) {
        this.name = Objects.requireNonNull(newName, "newName");
    }

    public void deactivate() {
        this.status = PricingStatus.INACTIVE;
    }

    public void activate() {
        this.status = PricingStatus.ACTIVE;
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

    public String code() {
        return code;
    }

    public String name() {
        return name;
    }

    public PricingStatus status() {
        return status;
    }

    public long version() {
        return version != null ? version : 0;
    }

    public Instant createdAt() {
        return audit.createdAt();
    }
}
