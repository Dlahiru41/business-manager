package com.bmp.pricing.domain.model;

import com.bmp.pricing.domain.error.PricingErrorCode;
import com.bmp.shared.audit.Auditable;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.FieldError;
import com.bmp.shared.error.ValidationFailure;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A tax rate of a category over business dates {@code [validFrom, validTo)} (PRC-07). The percentage never changes: a
 * new rate is a new record, and the old one gets an end date. Compound rates are charged on the amount including the
 * non-compound taxes.
 */
@Entity
@Table(schema = "pricing", name = "tax_rate")
public class TaxRate implements Auditable {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    @Id
    private UUID id;

    @org.hibernate.annotations.TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "tax_category_id", nullable = false, updatable = false)
    private UUID taxCategoryId;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "rate_pct", nullable = false, updatable = false, precision = 7, scale = 4)
    private BigDecimal ratePct;

    @Column(nullable = false, updatable = false)
    private boolean compound;

    @Column(name = "valid_from", nullable = false, updatable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private @Nullable LocalDate validTo;

    /** Null until first stored, so Spring Data persists (not merges) new aggregates with assigned ids. */
    @Version
    private @Nullable Long version;

    @Embedded
    private AuditStamps audit = new AuditStamps();

    @SuppressWarnings("NullAway.Init") // JPA
    protected TaxRate() {}

    private TaxRate(
            UUID id,
            TenantId tenant,
            UUID taxCategoryId,
            String code,
            String name,
            BigDecimal ratePct,
            boolean compound,
            LocalDate validFrom,
            @Nullable LocalDate validTo) {
        if (ratePct.signum() < 0 || ratePct.compareTo(HUNDRED) > 0) {
            throw new ValidationFailure(List.of(new FieldError("ratePct", "RANGE", "must be between 0 and 100")));
        }
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = tenant.value();
        this.taxCategoryId = Objects.requireNonNull(taxCategoryId, "taxCategoryId");
        this.code = code.toUpperCase(Locale.ROOT);
        this.name = Objects.requireNonNull(name, "name");
        this.ratePct = ratePct;
        this.compound = compound;
        this.validFrom = Objects.requireNonNull(validFrom, "validFrom");
        endOn(validTo);
    }

    public static TaxRate create(
            UUID id,
            TenantId tenant,
            UUID taxCategoryId,
            String code,
            String name,
            BigDecimal ratePct,
            boolean compound,
            LocalDate validFrom,
            @Nullable LocalDate validTo) {
        return new TaxRate(id, tenant, taxCategoryId, code, name, ratePct, compound, validFrom, validTo);
    }

    public void rename(String newName) {
        this.name = Objects.requireNonNull(newName, "newName");
    }

    /** Ends the rate before {@code date} (exclusive); {@code null} makes it open-ended again. */
    public void endOn(@Nullable LocalDate date) {
        if (date != null && !date.isAfter(validFrom)) {
            throw new BusinessRuleViolation(
                    PricingErrorCode.INVALID_VALIDITY,
                    Map.of("validFrom", validFrom.toString(), "validTo", date.toString()));
        }
        this.validTo = date;
    }

    /** Whether the rate applies on a business date (inclusive start, exclusive end). */
    public boolean appliesOn(LocalDate date) {
        return !validFrom.isAfter(date) && (validTo == null || date.isBefore(validTo));
    }

    /** Whether another rate of the same category and code overlaps this one's dates. */
    public boolean overlaps(TaxRate other) {
        if (other.id.equals(id) || !other.taxCategoryId.equals(taxCategoryId) || !other.code.equals(code)) {
            return false;
        }
        boolean otherStartsBeforeThisEnds = validTo == null || other.validFrom.isBefore(validTo);
        boolean thisStartsBeforeOtherEnds = other.validTo == null || validFrom.isBefore(other.validTo);
        return otherStartsBeforeThisEnds && thisStartsBeforeOtherEnds;
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

    public UUID taxCategoryId() {
        return taxCategoryId;
    }

    public String code() {
        return code;
    }

    public String name() {
        return name;
    }

    public BigDecimal ratePct() {
        return ratePct;
    }

    public boolean compound() {
        return compound;
    }

    public LocalDate validFrom() {
        return validFrom;
    }

    public @Nullable LocalDate validTo() {
        return validTo;
    }

    public long version() {
        return version != null ? version : 0;
    }

    public Instant createdAt() {
        return audit.createdAt();
    }
}
