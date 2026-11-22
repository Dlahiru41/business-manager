package com.bmp.pricing.domain.model;

import com.bmp.pricing.domain.error.PricingErrorCode;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A quantity break, customer-group rule, promotion or markup applied after the list price (PRC-06), in ascending
 * {@code priority}. At most one promotion applies to a line unless later promotions are {@code stackable}.
 */
@Entity
@Table(schema = "pricing", name = "price_rule")
public class PriceRule implements Auditable {

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
    @Column(nullable = false, updatable = false)
    private RuleKind kind;

    @Embedded
    private RuleConditions conditions;

    @Embedded
    private RuleEffect effect;

    @Column(nullable = false)
    private int priority;

    @Column(nullable = false)
    private boolean stackable;

    @Column(name = "valid_from")
    private @Nullable Instant validFrom;

    @Column(name = "valid_to")
    private @Nullable Instant validTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PricingStatus status;

    /** Null until first stored, so Spring Data persists (not merges) new aggregates with assigned ids. */
    @Version
    private @Nullable Long version;

    @Embedded
    private AuditStamps audit = new AuditStamps();

    @SuppressWarnings("NullAway.Init") // JPA
    protected PriceRule() {}

    private PriceRule(
            UUID id,
            TenantId tenant,
            String code,
            String name,
            RuleKind kind,
            RuleConditions conditions,
            RuleEffect effect,
            int priority,
            boolean stackable,
            @Nullable Instant validFrom,
            @Nullable Instant validTo) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = tenant.value();
        this.code = code.toUpperCase(Locale.ROOT);
        this.name = Objects.requireNonNull(name, "name");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.conditions = Objects.requireNonNull(conditions, "conditions");
        this.effect = Objects.requireNonNull(effect, "effect");
        this.priority = priority;
        this.stackable = stackable;
        this.status = PricingStatus.ACTIVE;
        validity(validFrom, validTo);
    }

    public static PriceRule create(
            UUID id,
            TenantId tenant,
            String code,
            String name,
            RuleKind kind,
            RuleConditions conditions,
            RuleEffect effect,
            int priority,
            boolean stackable,
            @Nullable Instant validFrom,
            @Nullable Instant validTo) {
        return new PriceRule(id, tenant, code, name, kind, conditions, effect, priority, stackable, validFrom, validTo);
    }

    public void rename(String newName) {
        this.name = Objects.requireNonNull(newName, "newName");
    }

    public void changeConditions(RuleConditions newConditions) {
        this.conditions = Objects.requireNonNull(newConditions, "newConditions");
    }

    public void changeEffect(RuleEffect newEffect) {
        this.effect = Objects.requireNonNull(newEffect, "newEffect");
    }

    public void reprioritise(int newPriority, boolean newStackable) {
        this.priority = newPriority;
        this.stackable = newStackable;
    }

    /** Sets the validity window; the end is after the start (exclusive end). */
    public void validity(@Nullable Instant from, @Nullable Instant to) {
        if (from != null && to != null && !to.isAfter(from)) {
            throw new BusinessRuleViolation(
                    PricingErrorCode.INVALID_VALIDITY, Map.of("validFrom", from.toString(), "validTo", to.toString()));
        }
        this.validFrom = from;
        this.validTo = to;
    }

    public void deactivate() {
        this.status = PricingStatus.INACTIVE;
    }

    public void activate() {
        this.status = PricingStatus.ACTIVE;
    }

    /** Active and valid at the moment (inclusive start, exclusive end). */
    public boolean appliesAt(Instant at) {
        return status == PricingStatus.ACTIVE
                && (validFrom == null || !validFrom.isAfter(at))
                && (validTo == null || at.isBefore(validTo));
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

    public RuleKind kind() {
        return kind;
    }

    public RuleConditions conditions() {
        return conditions;
    }

    public RuleEffect effect() {
        return effect;
    }

    public int priority() {
        return priority;
    }

    public boolean stackable() {
        return stackable;
    }

    public @Nullable Instant validFrom() {
        return validFrom;
    }

    public @Nullable Instant validTo() {
        return validTo;
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
