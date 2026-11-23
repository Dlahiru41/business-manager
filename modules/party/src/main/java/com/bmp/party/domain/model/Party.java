package com.bmp.party.domain.model;

import com.bmp.party.domain.error.PartyErrorCode;
import com.bmp.shared.audit.Auditable;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import com.bmp.shared.security.Sensitive;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A customer or supplier (PTY-01). Display name, phone, e-mail, tax id and address are personal data: anonymising
 * replaces them for good and keeps the id, type, group and credit terms, so documents keep their party (PTY-04).
 */
@Entity
@Table(schema = "party", name = "party")
@Sensitive
public class Party implements Auditable {

    /** The name an anonymised party shows. */
    public static final String ANONYMISED_NAME = "Anonymised party";

    @Id
    private UUID id;

    @org.hibernate.annotations.TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PartyType type;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    private @Nullable String phone;

    private @Nullable String email;

    @Column(name = "tax_id")
    private @Nullable String taxId;

    @Embedded
    private @Nullable Address address;

    @Column(name = "customer_group_id")
    private @Nullable UUID customerGroupId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PartyStatus status;

    @Column(nullable = false)
    private boolean anonymised;

    @Column(name = "anonymised_at")
    private @Nullable Instant anonymisedAt;

    @Embedded
    private CreditPolicy creditPolicy;

    /** Null until first stored, so Spring Data persists (not merges) new aggregates with assigned ids. */
    @Version
    private @Nullable Long version;

    @Embedded
    private AuditStamps audit = new AuditStamps();

    @SuppressWarnings("NullAway.Init") // JPA
    protected Party() {}

    private Party(UUID id, TenantId tenant, PartyType type, String displayName) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = tenant.value();
        this.type = Objects.requireNonNull(type, "type");
        this.displayName = name(displayName);
        this.status = PartyStatus.ACTIVE;
        this.creditPolicy = CreditPolicy.none();
    }

    /**
     * Rule PTY-01: a new active party without credit.
     *
     * @param phone already in E.164 form ({@link PhoneNumbers#normalise})
     */
    public static Party create(
            UUID id,
            TenantId tenant,
            PartyType type,
            String displayName,
            @Nullable String phone,
            @Nullable String email,
            @Nullable String taxId,
            @Nullable Address address,
            @Nullable UUID customerGroupId) {
        Party party = new Party(id, tenant, type, displayName);
        party.phone = phone;
        party.email = email;
        party.taxId = taxId;
        party.address = address == null || address.isEmpty() ? null : address;
        party.customerGroupId = customerGroupId;
        return party;
    }

    public void changeType(PartyType newType) {
        requireNotAnonymised();
        this.type = Objects.requireNonNull(newType, "newType");
    }

    public void rename(String newName) {
        requireNotAnonymised();
        this.displayName = name(newName);
    }

    /** Changes the phone, already in E.164 form. */
    public void changePhone(String newPhone) {
        requireNotAnonymised();
        this.phone = Objects.requireNonNull(newPhone, "newPhone");
    }

    public void changeEmail(String newEmail) {
        requireNotAnonymised();
        this.email = Objects.requireNonNull(newEmail, "newEmail");
    }

    public void changeTaxId(String newTaxId) {
        requireNotAnonymised();
        this.taxId = Objects.requireNonNull(newTaxId, "newTaxId");
    }

    public void changeAddress(Address newAddress) {
        requireNotAnonymised();
        this.address = newAddress.isEmpty() ? null : newAddress;
    }

    public void assignGroup(UUID groupId) {
        requireNotAnonymised();
        this.customerGroupId = Objects.requireNonNull(groupId, "groupId");
    }

    public void deactivate() {
        this.status = PartyStatus.INACTIVE;
    }

    public void activate() {
        requireNotAnonymised();
        this.status = PartyStatus.ACTIVE;
    }

    /** Rule PTY-03: new credit terms (the permission is checked by the caller). */
    public void changeCreditPolicy(CreditPolicy newPolicy) {
        requireNotAnonymised();
        this.creditPolicy = Objects.requireNonNull(newPolicy, "newPolicy");
    }

    /**
     * Rule PTY-04: replaces the personal data and deactivates the party.
     *
     * @return false when it was anonymised already (nothing changes)
     */
    public boolean anonymise(Instant at) {
        if (anonymised) {
            return false;
        }
        this.displayName = ANONYMISED_NAME;
        this.phone = null;
        this.email = null;
        this.taxId = null;
        this.address = null;
        this.anonymised = true;
        this.anonymisedAt = at;
        this.status = PartyStatus.INACTIVE;
        return true;
    }

    private void requireNotAnonymised() {
        if (anonymised) {
            throw new BusinessRuleViolation(
                    PartyErrorCode.PARTY_ANONYMISED, "An anonymised party cannot change", Map.of("partyId", id));
        }
    }

    private static String name(String name) {
        String trimmed = name.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("A display name is required");
        }
        return trimmed;
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

    public PartyType type() {
        return type;
    }

    public String displayName() {
        return displayName;
    }

    public @Nullable String phone() {
        return phone;
    }

    public @Nullable String email() {
        return email;
    }

    public @Nullable String taxId() {
        return taxId;
    }

    public @Nullable Address address() {
        return address;
    }

    public @Nullable UUID customerGroupId() {
        return customerGroupId;
    }

    public PartyStatus status() {
        return status;
    }

    public boolean isActive() {
        return status == PartyStatus.ACTIVE;
    }

    public boolean anonymised() {
        return anonymised;
    }

    public CreditPolicy creditPolicy() {
        return creditPolicy;
    }

    /** The optimistic-lock version; 0 before the first save. */
    public long version() {
        return version != null ? version : 0;
    }

    public AuditStamps audit() {
        return audit;
    }

    @Override
    public String toString() {
        return "Party[id=" + id + ", type=" + type + "]";
    }
}
