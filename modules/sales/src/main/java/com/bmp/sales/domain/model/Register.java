package com.bmp.sales.domain.model;

import com.bmp.shared.audit.Auditable;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A billing counter at a location, bound to one device; numbers its bills with its prefix (SAL-01, SAL-12). */
@Entity
@Table(schema = "sales", name = "register")
public class Register implements Auditable {

    @Id
    private UUID id;

    @org.hibernate.annotations.TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private String code;

    private @Nullable String name;

    @Column(name = "location_id", nullable = false, updatable = false)
    private UUID locationId;

    @Column(name = "device_id")
    private @Nullable UUID deviceId;

    @Column(name = "bill_prefix", nullable = false)
    private String billPrefix;

    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(name = "last_number", nullable = false)
    private long lastNumber;

    @Version
    private @Nullable Long version;

    @Embedded
    private AuditStamps audit = new AuditStamps();

    @SuppressWarnings("NullAway.Init") // JPA
    protected Register() {}

    public static Register create(
            UUID id,
            TenantId tenant,
            String code,
            @Nullable String name,
            UUID locationId,
            @Nullable UUID deviceId,
            String billPrefix) {
        Register register = new Register();
        register.id = Objects.requireNonNull(id, "id");
        register.tenantId = tenant.value();
        register.code = code.toUpperCase(java.util.Locale.ROOT);
        register.name = name;
        register.locationId = locationId;
        register.deviceId = deviceId;
        register.billPrefix = billPrefix;
        return register;
    }

    public void rename(String newName) {
        this.name = newName;
    }

    public void bindDevice(@Nullable UUID device) {
        this.deviceId = device;
    }

    public void changePrefix(String prefix) {
        this.billPrefix = prefix;
    }

    public void changeStatus(String newStatus) {
        this.status = newStatus;
    }

    /** Rule SAL-01: remembers the highest bill sequence accepted, which the till continues from. */
    public void accepted(long sequence) {
        this.lastNumber = Math.max(lastNumber, sequence);
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
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

    public @Nullable String name() {
        return name;
    }

    public UUID locationId() {
        return locationId;
    }

    public @Nullable UUID deviceId() {
        return deviceId;
    }

    public String billPrefix() {
        return billPrefix;
    }

    public String status() {
        return status;
    }

    public long lastNumber() {
        return lastNumber;
    }

    public long version() {
        return version != null ? version : 0;
    }

    public AuditStamps audit() {
        return audit;
    }
}
