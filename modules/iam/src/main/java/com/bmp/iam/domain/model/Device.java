package com.bmp.iam.domain.model;

import com.bmp.iam.api.events.DeviceRegistered;
import com.bmp.iam.domain.error.IamErrorCode;
import com.bmp.shared.audit.Auditable;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.event.EventRecorder;
import com.bmp.shared.event.EventStampSource;
import com.bmp.shared.id.DeviceId;
import com.bmp.shared.id.LocationId;
import com.bmp.shared.id.RegisterId;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
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
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A registered till (aggregate root). Its credential is a confidential client in the identity provider; the secret is
 * shown once at registration and never stored. Revocation is final (IAM-03: its tokens are rejected at once).
 */
@Entity
@Table(schema = "iam", name = "device")
public class Device implements Auditable {

    /** Prefix of the identity provider client id of every device. */
    public static final String CLIENT_ID_PREFIX = "device-";

    @Id
    private UUID id;

    @org.hibernate.annotations.TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @Column(name = "register_id")
    private @Nullable UUID registerId;

    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Column(name = "idp_client_id", nullable = false, updatable = false)
    private String idpClientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeviceStatus status;

    @Column(name = "registered_at", nullable = false, updatable = false)
    private Instant registeredAt;

    @Column(name = "revoked_at")
    private @Nullable Instant revokedAt;

    @Column(name = "revoke_reason")
    private @Nullable String revokeReason;

    @Column(name = "last_seen_at")
    private @Nullable Instant lastSeenAt;

    /** Null until first stored, so Spring Data persists (not merges) new aggregates with assigned ids. */
    @Version
    private @Nullable Long version;

    @Embedded
    private AuditStamps audit = new AuditStamps();

    @Transient
    private final EventRecorder events = new EventRecorder();

    @SuppressWarnings("NullAway.Init") // JPA
    protected Device() {}

    private Device(
            DeviceId id,
            TenantId tenant,
            String name,
            LocationId location,
            @Nullable RegisterId register,
            Instant registeredAt) {
        this.id = id.value();
        this.tenantId = tenant.value();
        this.name = Objects.requireNonNull(name, "name");
        this.locationId = location.value();
        this.registerId = register != null ? register.value() : null;
        this.idpClientId = clientIdOf(id);
        this.status = DeviceStatus.ACTIVE;
        this.registeredAt = registeredAt;
    }

    /** The identity provider client id of a device: stable and derived from its id. */
    public static String clientIdOf(DeviceId id) {
        return CLIENT_ID_PREFIX + id.value();
    }

    /** Registers an active device whose client already exists in the identity provider; records the event. */
    public static Device register(
            DeviceId id,
            TenantId tenant,
            String name,
            LocationId location,
            @Nullable RegisterId register,
            EventStampSource stamps) {
        var stamp = stamps.next();
        Device device = new Device(id, tenant, name, location, register, stamp.occurredAt());
        device.events.record(new DeviceRegistered(
                stamp.eventId(), tenant, stamp.occurredAt(), id.value(), 0, stamp.metadata(), id, register, location));
        return device;
    }

    /** Revokes the device for good. */
    public void revoke(String reason, Instant at) {
        if (status == DeviceStatus.REVOKED) {
            throw new BusinessRuleViolation(IamErrorCode.DEVICE_ALREADY_REVOKED, Map.of("deviceId", id));
        }
        this.status = DeviceStatus.REVOKED;
        this.revokedAt = Objects.requireNonNull(at, "at");
        this.revokeReason = Objects.requireNonNull(reason, "reason");
    }

    public boolean isActive() {
        return status == DeviceStatus.ACTIVE;
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

    public DeviceId id() {
        return DeviceId.of(id);
    }

    public String name() {
        return name;
    }

    public @Nullable UUID registerId() {
        return registerId;
    }

    public UUID locationId() {
        return locationId;
    }

    public String idpClientId() {
        return idpClientId;
    }

    public DeviceStatus status() {
        return status;
    }

    public Instant registeredAt() {
        return registeredAt;
    }

    public @Nullable Instant revokedAt() {
        return revokedAt;
    }

    public long version() {
        return version != null ? version : 0;
    }
}
