package com.bmp.tenant.domain.model;

import com.bmp.shared.audit.Auditable;
import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.event.EventRecorder;
import com.bmp.shared.event.EventStampSource;
import com.bmp.shared.id.LocationId;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import com.bmp.tenant.api.events.LocationCreated;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * A shop, store room or warehouse of a tenant (aggregate root). Stock is held per location. Locations are never
 * deleted, only deactivated (TEN-01 keeps at least one active).
 */
@Entity
@Table(schema = "tenant", name = "location")
public class Location implements Auditable {

    /** Name of the location every new tenant starts with (TEN-01). */
    public static final String MAIN_LOCATION_NAME = "Main shop";

    @Id
    private UUID id;

    @org.hibernate.annotations.TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LocationType type;

    /** Address as a JSON document (schema-validated at the API edge). */
    @Column
    @JdbcTypeCode(SqlTypes.JSON)
    private @Nullable String address;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LocationStatus status;

    @Version
    private long version;

    @Embedded
    private AuditStamps audit = new AuditStamps();

    @Transient
    private final EventRecorder events = new EventRecorder();

    @SuppressWarnings("NullAway.Init") // JPA
    protected Location() {}

    private Location(LocationId id, TenantId tenant, String name, LocationType type, @Nullable String address) {
        this.id = id.value();
        this.tenantId = tenant.value();
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
        this.address = address;
        this.status = LocationStatus.ACTIVE;
    }

    /** Creates an active location and records {@link LocationCreated}. */
    public static Location create(
            LocationId id,
            TenantId tenant,
            String name,
            LocationType type,
            @Nullable String address,
            EventStampSource stamps) {
        Location location = new Location(id, tenant, name, type, address);
        var stamp = stamps.next();
        location.events.record(new LocationCreated(
                stamp.eventId(), tenant, stamp.occurredAt(), id.value(), 0, stamp.metadata(), id, name, type.name()));
        return location;
    }

    public void rename(String newName) {
        this.name = Objects.requireNonNull(newName, "newName");
    }

    public void changeType(LocationType newType) {
        this.type = Objects.requireNonNull(newType, "newType");
    }

    public void changeAddress(@Nullable String newAddress) {
        this.address = newAddress;
    }

    /** Deactivation is checked against TEN-01 by the caller, which can see the tenant's other locations. */
    public void deactivate() {
        this.status = LocationStatus.INACTIVE;
    }

    public void activate() {
        this.status = LocationStatus.ACTIVE;
    }

    public boolean isActive() {
        return status == LocationStatus.ACTIVE;
    }

    public List<DomainEvent> pullEvents() {
        return events.drain();
    }

    @Override
    public void recordCreation(Instant at, @Nullable UserId by) {
        audit.created(at, by);
    }

    @Override
    public void recordModification(Instant at, @Nullable UserId by) {
        audit.modified(at, by);
    }

    public LocationId id() {
        return LocationId.of(id);
    }

    public String name() {
        return name;
    }

    public LocationType type() {
        return type;
    }

    public @Nullable String address() {
        return address;
    }

    public LocationStatus status() {
        return status;
    }

    public long version() {
        return version;
    }

    public Instant createdAt() {
        return audit.createdAt();
    }
}
