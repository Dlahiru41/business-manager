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
 * A named, versioned set of prices (PRC-01, PRC-03): for a channel, optionally for one customer group or one
 * customer. Every change of its prices takes the next {@code currentVersion}; bills record it (PRC-10). Whether prices
 * include tax is fixed at creation, since it changes what every stored amount means.
 */
@Entity
@Table(schema = "pricing", name = "price_list")
public class PriceList implements Auditable {

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
    private ListChannel channel;

    @Column(name = "customer_group_id", updatable = false)
    private @Nullable UUID customerGroupId;

    @Column(name = "customer_id", updatable = false)
    private @Nullable UUID customerId;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Column(name = "tax_inclusive", nullable = false, updatable = false)
    private boolean taxInclusive;

    @Column(nullable = false)
    private int priority;

    @Column(name = "current_version", nullable = false)
    private int currentVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PricingStatus status;

    /** Null until first stored, so Spring Data persists (not merges) new aggregates with assigned ids. */
    @Version
    private @Nullable Long version;

    @Embedded
    private AuditStamps audit = new AuditStamps();

    @SuppressWarnings("NullAway.Init") // JPA
    protected PriceList() {}

    private PriceList(
            UUID id,
            TenantId tenant,
            String code,
            String name,
            ListChannel channel,
            @Nullable UUID customerGroupId,
            @Nullable UUID customerId,
            String currency,
            boolean taxInclusive,
            int priority) {
        if (customerGroupId != null && customerId != null) {
            throw new BusinessRuleViolation(
                    PricingErrorCode.INVALID_PRICE_LIST_SCOPE,
                    "A price list is for a customer group or a customer, not both",
                    Map.of("code", code));
        }
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = tenant.value();
        this.code = code.toUpperCase(Locale.ROOT);
        this.name = Objects.requireNonNull(name, "name");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.customerGroupId = customerGroupId;
        this.customerId = customerId;
        this.currency = Objects.requireNonNull(currency, "currency");
        this.taxInclusive = taxInclusive;
        this.priority = priority;
        this.status = PricingStatus.ACTIVE;
    }

    /** Rule PRC-01: a new list at version 0, without prices. */
    public static PriceList create(
            UUID id,
            TenantId tenant,
            String code,
            String name,
            ListChannel channel,
            @Nullable UUID customerGroupId,
            @Nullable UUID customerId,
            String currency,
            boolean taxInclusive,
            int priority) {
        return new PriceList(
                id, tenant, code, name, channel, customerGroupId, customerId, currency, taxInclusive, priority);
    }

    public void rename(String newName) {
        this.name = Objects.requireNonNull(newName, "newName");
    }

    public void reprioritise(int newPriority) {
        this.priority = newPriority;
    }

    public void deactivate() {
        this.status = PricingStatus.INACTIVE;
    }

    public void activate() {
        this.status = PricingStatus.ACTIVE;
    }

    /** Rule PRC-03: takes the next price version for a change of this list's prices. */
    public int nextVersion() {
        currentVersion++;
        return currentVersion;
    }

    /** The resolution tier of this list (PRC-04). */
    public ListScope scope() {
        if (customerId != null) {
            return ListScope.CUSTOMER;
        }
        if (customerGroupId != null) {
            return ListScope.CUSTOMER_GROUP;
        }
        return channel == ListChannel.ANY ? ListScope.ANY : ListScope.CHANNEL;
    }

    /**
     * Whether this active list may price a sale in this channel for this customer and group (PRC-04).
     *
     * @param customer the bill's customer, if any
     * @param group the customer's group, if any
     */
    public boolean serves(SalesChannel sale, @Nullable UUID customer, @Nullable UUID group) {
        if (!isActive()) {
            return false;
        }
        boolean channelMatches = channel == ListChannel.ANY || channel.name().equals(sale.name());
        return switch (scope()) {
            case CUSTOMER -> channelMatches && Objects.equals(customerId, customer);
            case CUSTOMER_GROUP -> channelMatches && Objects.equals(customerGroupId, group);
            case CHANNEL, ANY -> channelMatches;
        };
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

    public ListChannel channel() {
        return channel;
    }

    public @Nullable UUID customerGroupId() {
        return customerGroupId;
    }

    public @Nullable UUID customerId() {
        return customerId;
    }

    public String currency() {
        return currency;
    }

    public boolean taxInclusive() {
        return taxInclusive;
    }

    public int priority() {
        return priority;
    }

    public int currentVersion() {
        return currentVersion;
    }

    public PricingStatus status() {
        return status;
    }

    public boolean isActive() {
        return status == PricingStatus.ACTIVE;
    }

    public long version() {
        return version != null ? version : 0;
    }

    public Instant createdAt() {
        return audit.createdAt();
    }
}
