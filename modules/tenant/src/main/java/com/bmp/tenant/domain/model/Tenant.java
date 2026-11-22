package com.bmp.tenant.domain.model;

import com.bmp.shared.audit.Auditable;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.event.EventRecorder;
import com.bmp.shared.event.EventStampSource;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import com.bmp.shared.money.CurrencyCode;
import com.bmp.tenant.api.events.TenantCreated;
import com.bmp.tenant.domain.error.TenantErrorCode;
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
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * A business using the platform (aggregate root). Its id is the tenant id every other row carries. Currency, time
 * zone and business-day cut-off mirror the {@code general} settings group, which is where they are changed.
 */
@Entity
@Table(schema = "tenant", name = "tenant")
public class Tenant implements Auditable {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "legal_name")
    private @Nullable String legalName;

    @Column(name = "business_type", nullable = false)
    private String businessType;

    @Column(nullable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Column(name = "time_zone", nullable = false)
    private String timeZone;

    @Column(name = "business_day_cutoff_hour", nullable = false)
    private int businessDayCutoffHour;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TenantStatus status;

    private @Nullable String plan;

    @Version
    private long version;

    @Embedded
    private AuditStamps audit = new AuditStamps();

    @Transient
    private final EventRecorder events = new EventRecorder();

    @SuppressWarnings("NullAway.Init") // JPA
    protected Tenant() {}

    private Tenant(TenantId id, TenantProfile profile, Regional regional, @Nullable String plan) {
        this.id = id.value();
        this.name = profile.name();
        this.legalName = profile.legalName();
        this.businessType = profile.businessType();
        this.currency = regional.currency().code();
        this.timeZone = regional.timeZone().getId();
        this.businessDayCutoffHour = regional.businessDayCutoffHour();
        this.status = TenantStatus.ACTIVE;
        this.plan = plan;
    }

    /** Creates an active tenant and records {@link TenantCreated}. */
    public static Tenant create(
            TenantId id,
            TenantProfile profile,
            Regional regional,
            @Nullable String plan,
            @Nullable String templatePackId,
            EventStampSource stamps) {
        Tenant tenant = new Tenant(id, profile, regional, plan);
        var stamp = stamps.next();
        tenant.events.record(new TenantCreated(
                stamp.eventId(),
                id,
                stamp.occurredAt(),
                id.value(),
                0,
                stamp.metadata(),
                profile.businessType(),
                regional.currency().code(),
                regional.timeZone().getId(),
                regional.businessDayCutoffHour(),
                templatePackId));
        return tenant;
    }

    /** Replaces the descriptive profile; currency and time zone are changed through settings. */
    public void updateProfile(TenantProfile profile) {
        this.name = profile.name();
        this.legalName = profile.legalName();
        this.businessType = profile.businessType();
    }

    /** Mirrors the {@code general} settings group. */
    public void changeRegional(Regional regional) {
        this.currency = regional.currency().code();
        this.timeZone = regional.timeZone().getId();
        this.businessDayCutoffHour = regional.businessDayCutoffHour();
    }

    /** TEN-04: a suspended tenant keeps read-only access. Only active tenants can be suspended. */
    public void suspend() {
        requireStatus(TenantStatus.ACTIVE, TenantStatus.SUSPENDED);
        this.status = TenantStatus.SUSPENDED;
    }

    public void reactivate() {
        requireStatus(TenantStatus.SUSPENDED, TenantStatus.ACTIVE);
        this.status = TenantStatus.ACTIVE;
    }

    private void requireStatus(TenantStatus required, TenantStatus target) {
        if (status != required) {
            throw new BusinessRuleViolation(
                    TenantErrorCode.INVALID_TENANT_STATUS_TRANSITION,
                    Map.of("from", status.name(), "to", target.name()));
        }
    }

    /** Whether the tenant existed before {@code instant} (used by the default TEN-02 activity probe). */
    public boolean createdBefore(Instant instant) {
        return audit.createdAt().isBefore(instant);
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

    public TenantId id() {
        return TenantId.of(id);
    }

    public TenantProfile profile() {
        return new TenantProfile(name, legalName, businessType);
    }

    public Regional regional() {
        return new Regional(CurrencyCode.of(currency), ZoneId.of(timeZone), businessDayCutoffHour);
    }

    public TenantStatus status() {
        return status;
    }

    public @Nullable String plan() {
        return plan;
    }

    public long version() {
        return version;
    }

    public Instant createdAt() {
        return audit.createdAt();
    }

    /**
     * Descriptive data of a tenant.
     *
     * @param name trading name
     * @param legalName registered name, if different
     * @param businessType free classification, e.g. {@code RETAIL_GROCERY}
     */
    public record TenantProfile(String name, @Nullable String legalName, String businessType) {
        public TenantProfile {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(businessType, "businessType");
        }
    }

    /**
     * Regional settings fixed for business documents (TEN-02).
     *
     * @param currency ISO 4217 currency
     * @param timeZone tenant time zone for business dates
     * @param businessDayCutoffHour 0–23
     */
    public record Regional(CurrencyCode currency, ZoneId timeZone, int businessDayCutoffHour) {
        public Regional {
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(timeZone, "timeZone");
            if (businessDayCutoffHour < 0 || businessDayCutoffHour > 23) {
                throw new IllegalArgumentException("businessDayCutoffHour must be 0-23");
            }
        }
    }
}
