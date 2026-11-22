package com.bmp.catalog.domain.model;

import com.bmp.catalog.domain.error.CatalogErrorCode;
import com.bmp.shared.audit.Auditable;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.FieldError;
import com.bmp.shared.error.NotFoundException;
import com.bmp.shared.error.ValidationFailure;
import com.bmp.shared.id.ProductId;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * A general thing the business deals in (Eggs, Cooking oil) — aggregate root of its attribute types, exclusions and
 * conversions. Variants are their own aggregates referring to the product by id. The base unit is fixed at creation
 * (CAT-02): every quantity in base units depends on it. Attribute types are fixed once variants exist; values can be
 * added at any time (then regenerate the matrix, CAT-05).
 */
@Entity
@Table(schema = "catalog", name = "product")
public class Product implements Auditable {

    public static final int MAX_ATTRIBUTES = 5;
    public static final int MAX_CONVERSIONS = 20;

    @Id
    private UUID id;

    @org.hibernate.annotations.TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "category_id")
    private @Nullable UUID categoryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ProductTemplate template;

    @Column(name = "base_uom_id", nullable = false, updatable = false)
    private UUID baseUnitId;

    @Embedded
    private Tracking tracking;

    @Column(name = "tax_category_id")
    private @Nullable UUID taxCategoryId;

    /** Free product facts as a JSON object of strings (validated at the API edge); used by no rule. */
    @Column(nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String properties;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CatalogStatus status;

    @OneToMany(cascade = CascadeType.ALL)
    @JoinColumn(name = "product_id", nullable = false)
    @OrderBy("sort ASC, code ASC")
    private List<AttributeType> attributes = new ArrayList<>();

    @ElementCollection
    @CollectionTable(schema = "catalog", name = "variant_exclusion", joinColumns = @JoinColumn(name = "product_id"))
    private Set<Exclusion> exclusions = new HashSet<>();

    @ElementCollection
    @CollectionTable(schema = "catalog", name = "product_conversion", joinColumns = @JoinColumn(name = "product_id"))
    private Set<ProductConversion> conversions = new HashSet<>();

    /** The catalog version of the last change (sync deltas, CAT-13); set on every change so the version moves. */
    @Column(nullable = false)
    private long revision;

    /** Null until first stored, so Spring Data persists (not merges) new aggregates with assigned ids. */
    @Version
    private @Nullable Long version;

    @Embedded
    private AuditStamps audit = new AuditStamps();

    @SuppressWarnings("NullAway.Init") // JPA
    protected Product() {}

    private Product(ProductId id, TenantId tenant, NewProduct spec, Supplier<UUID> ids) {
        this.id = id.value();
        this.tenantId = tenant.value();
        this.code = spec.code().toUpperCase(Locale.ROOT);
        this.name = spec.name();
        this.categoryId = spec.categoryId();
        this.template = spec.template();
        this.baseUnitId = spec.baseUnitId();
        this.tracking = spec.tracking();
        this.taxCategoryId = spec.taxCategoryId();
        this.properties = spec.propertiesJson();
        this.status = CatalogStatus.ACTIVE;
        if (spec.attributes().size() > MAX_ATTRIBUTES) {
            throw new ValidationFailure(List.of(new FieldError(
                    "attributes", "MAX_ITEMS", "a product has at most " + MAX_ATTRIBUTES + " attributes")));
        }
        for (AttributeDraft attribute : spec.attributes()) {
            addAttributeType(attribute, ids);
        }
    }

    /**
     * A new product with its attribute types; variants are created separately (they are their own aggregates).
     *
     * @param ids supplies ids for attribute types and values
     */
    public static Product create(ProductId id, TenantId tenant, NewProduct spec, Supplier<UUID> ids) {
        return new Product(id, tenant, spec, ids);
    }

    public void rename(String newName) {
        this.name = Objects.requireNonNull(newName, "newName");
    }

    public void changeCategory(@Nullable UUID newCategoryId) {
        this.categoryId = newCategoryId;
    }

    public void changeTaxCategory(@Nullable UUID newTaxCategoryId) {
        this.taxCategoryId = newTaxCategoryId;
    }

    public void changeTracking(Tracking newTracking) {
        this.tracking = Objects.requireNonNull(newTracking, "newTracking");
    }

    public void replaceProperties(String propertiesJson) {
        this.properties = Objects.requireNonNull(propertiesJson, "propertiesJson");
    }

    /**
     * Adds an attribute type. Only while the product has no variants: existing variants would have no value for it.
     *
     * @param hasVariants whether any variant of this product exists
     */
    public AttributeType addAttribute(AttributeDraft draft, boolean hasVariants, Supplier<UUID> ids) {
        if (hasVariants) {
            throw new BusinessRuleViolation(
                    CatalogErrorCode.ATTRIBUTE_TYPES_LOCKED, Map.of("productId", id, "attributeCode", draft.code()));
        }
        if (attributes.size() >= MAX_ATTRIBUTES) {
            throw new ValidationFailure(List.of(new FieldError(
                    "attributes", "MAX_ITEMS", "a product has at most " + MAX_ATTRIBUTES + " attributes")));
        }
        return addAttributeType(draft, ids);
    }

    /**
     * Renames or re-sorts an attribute type, changes values or adds values (entries without an id).
     *
     * @return the changed attribute type
     */
    public AttributeType changeAttribute(
            UUID attributeId,
            @Nullable String newName,
            @Nullable Integer newSort,
            List<AttributeValueChange> valueChanges,
            Supplier<UUID> ids) {
        AttributeType attribute = attribute(attributeId)
                .orElseThrow(() -> new NotFoundException(
                        CatalogErrorCode.ATTRIBUTE_NOT_FOUND, Map.of("attributeId", attributeId)));
        attribute.change(newName, newSort);
        for (AttributeValueChange change : valueChanges) {
            if (change.id() != null) {
                attribute.changeValue(change.id(), change.value(), change.sort());
            } else if (change.value() != null) {
                attribute.addValue(new AttributeValueDraft(change.code(), change.value(), change.sort()), ids.get());
            } else {
                throw new ValidationFailure(List.of(new FieldError("values", "REQUIRED", "a new value needs a value")));
            }
        }
        return attribute;
    }

    /**
     * Resolves attribute value codes ({@code {"COLOUR": "RED", "SIZE": "L"}}) to value ids, in attribute order.
     *
     * @param complete whether every attribute must have a value (a variant combination) or a subset is enough (an
     *     exclusion)
     */
    public List<UUID> resolveValues(Map<String, String> codes, boolean complete) {
        Map<String, String> wanted = new LinkedHashMap<>();
        codes.forEach((attribute, value) -> wanted.put(attribute.toUpperCase(Locale.ROOT), value));
        List<UUID> valueIds = new ArrayList<>();
        for (AttributeType attribute : axes()) {
            String valueCode = wanted.remove(attribute.code());
            if (valueCode == null) {
                if (complete) {
                    throw unknownValue(attribute.code(), "");
                }
                continue;
            }
            valueIds.add(attribute
                    .findValueByCode(valueCode)
                    .orElseThrow(() -> unknownValue(attribute.code(), valueCode))
                    .id());
        }
        if (!wanted.isEmpty()) {
            Map.Entry<String, String> unknown = wanted.entrySet().iterator().next();
            throw unknownValue(unknown.getKey(), unknown.getValue());
        }
        if (valueIds.isEmpty()) {
            throw unknownValue("", "");
        }
        return valueIds;
    }

    /** Rule CAT-05: replaces the combinations never to generate. */
    public void replaceExclusions(Collection<Exclusion> newExclusions) {
        Set<Exclusion> valid = new HashSet<>(newExclusions);
        Set<UUID> known = new HashSet<>();
        attributes.forEach(a -> a.values().forEach(v -> known.add(v.id())));
        for (Exclusion exclusion : valid) {
            if (!known.containsAll(exclusion.valueIds())) {
                throw new BusinessRuleViolation(
                        CatalogErrorCode.UNKNOWN_ATTRIBUTE_VALUE, Map.of("valueIds", exclusion.valueIds()));
            }
        }
        exclusions.retainAll(valid);
        exclusions.addAll(valid);
    }

    public boolean isExcluded(Collection<UUID> combination) {
        return exclusions.stream().anyMatch(e -> e.excludes(combination));
    }

    /**
     * Rules CAT-03/CAT-14: replaces the product's conversions across dimensions. Factors are positive, a conversion
     * links two different units, and each pair of units appears once (either direction).
     */
    public void replaceConversions(List<ProductConversion> newConversions) {
        if (newConversions.size() > MAX_CONVERSIONS) {
            throw new BusinessRuleViolation(
                    CatalogErrorCode.INVALID_CONVERSION, Map.of("count", newConversions.size()));
        }
        List<ProductConversion> seen = new ArrayList<>();
        for (ProductConversion conversion : newConversions) {
            if (conversion.factor().signum() <= 0 || conversion.fromUnitId().equals(conversion.toUnitId())) {
                throw new BusinessRuleViolation(
                        CatalogErrorCode.INVALID_CONVERSION, Map.of("factor", conversion.factor()));
            }
            if (seen.stream().anyMatch(c -> c.links(conversion.fromUnitId(), conversion.toUnitId()))) {
                throw new BusinessRuleViolation(
                        CatalogErrorCode.INVALID_CONVERSION,
                        "Each pair of units has one conversion",
                        Map.of("fromUnitId", conversion.fromUnitId(), "toUnitId", conversion.toUnitId()));
            }
            seen.add(conversion);
        }
        conversions.clear();
        conversions.addAll(seen);
    }

    /** Rule CAT-11: deactivated, never deleted. */
    public void deactivate() {
        this.status = CatalogStatus.INACTIVE;
    }

    public void reactivate() {
        this.status = CatalogStatus.ACTIVE;
    }

    /** Records the catalog version of this change; every change takes one, so the aggregate version moves too. */
    public void touch(long catalogRevision) {
        this.revision = catalogRevision;
    }

    public Optional<AttributeType> attribute(UUID attributeId) {
        return attributes.stream().filter(a -> a.id().equals(attributeId)).findFirst();
    }

    /** The attribute types in matrix order. */
    public List<AttributeType> axes() {
        return attributes.stream()
                .sorted(Comparator.comparingInt(AttributeType::sort).thenComparing(AttributeType::code))
                .toList();
    }

    /** The attribute values of a combination, in attribute order. */
    public List<AttributeValue> valuesOf(Collection<UUID> valueIds) {
        List<AttributeValue> values = new ArrayList<>();
        for (AttributeType attribute : axes()) {
            for (AttributeValue value : attribute.values()) {
                if (valueIds.contains(value.id())) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    /** The attribute type a value belongs to. */
    public Optional<AttributeType> attributeOf(UUID valueId) {
        return attributes.stream().filter(a -> a.findValue(valueId).isPresent()).findFirst();
    }

    @Override
    public void recordCreation(Instant at, @Nullable UserId by) {
        audit.created(at, by);
    }

    @Override
    public void recordModification(Instant at, @Nullable UserId by) {
        audit.modified(at, by);
    }

    public ProductId id() {
        return ProductId.of(id);
    }

    public String code() {
        return code;
    }

    public String name() {
        return name;
    }

    public @Nullable UUID categoryId() {
        return categoryId;
    }

    public ProductTemplate template() {
        return template;
    }

    public UUID baseUnitId() {
        return baseUnitId;
    }

    public Tracking tracking() {
        return tracking;
    }

    public @Nullable UUID taxCategoryId() {
        return taxCategoryId;
    }

    public String propertiesJson() {
        return properties;
    }

    public CatalogStatus status() {
        return status;
    }

    public boolean isActive() {
        return status == CatalogStatus.ACTIVE;
    }

    public Set<Exclusion> exclusions() {
        return Set.copyOf(exclusions);
    }

    public List<ProductConversion> conversions() {
        return List.copyOf(conversions);
    }

    public long revision() {
        return revision;
    }

    public long version() {
        return version != null ? version : 0;
    }

    public Instant createdAt() {
        return audit.createdAt();
    }

    public Instant updatedAt() {
        return audit.updatedAt();
    }

    private AttributeType addAttributeType(AttributeDraft draft, Supplier<UUID> ids) {
        String attributeCode = draft.code().toUpperCase(Locale.ROOT);
        if (attributes.stream().anyMatch(a -> a.code().equals(attributeCode))) {
            throw new BusinessRuleViolation(
                    CatalogErrorCode.ATTRIBUTE_CODE_TAKEN, Map.of("attributeCode", attributeCode));
        }
        AttributeType attribute = new AttributeType(ids.get(), tenantId, draft, ids);
        attributes.add(attribute);
        return attribute;
    }

    private static BusinessRuleViolation unknownValue(String attributeCode, String valueCode) {
        return new BusinessRuleViolation(
                CatalogErrorCode.UNKNOWN_ATTRIBUTE_VALUE,
                Map.of("attributeCode", attributeCode, "valueCode", valueCode));
    }
}
