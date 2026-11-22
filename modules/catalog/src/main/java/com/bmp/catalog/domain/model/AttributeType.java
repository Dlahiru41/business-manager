package com.bmp.catalog.domain.model;

import com.bmp.catalog.domain.error.CatalogErrorCode;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.FieldError;
import com.bmp.shared.error.ValidationFailure;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A dimension of variation of a product (Colour, Size, Brand) with its allowed values. Part of the {@link Product}
 * aggregate. Values are never removed: their combinations are excluded instead (CAT-05, CAT-11).
 */
@Entity
@Table(schema = "catalog", name = "attribute_type")
public class AttributeType {

    public static final int MAX_VALUES = 100;

    private static final Pattern NOT_CODE_CHAR = Pattern.compile("[^A-Z0-9]+");

    @Id
    private UUID id;

    @org.hibernate.annotations.TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int sort;

    @OneToMany(cascade = CascadeType.ALL)
    @JoinColumn(name = "attribute_type_id", nullable = false)
    @OrderBy("sort ASC, code ASC")
    private List<AttributeValue> values = new ArrayList<>();

    @SuppressWarnings("NullAway.Init") // JPA
    protected AttributeType() {}

    AttributeType(UUID id, UUID tenantId, AttributeDraft draft, Supplier<UUID> ids) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.code = draft.code().toUpperCase(Locale.ROOT);
        this.name = draft.name();
        this.sort = draft.sort();
        for (AttributeValueDraft value : draft.values()) {
            addValue(value, ids.get());
        }
    }

    /**
     * A value code from its display value when none is given: upper case, other characters as {@code _}
     * ({@code Golden Yellow} → {@code GOLDEN_YELLOW}).
     */
    static String codeFor(@Nullable String code, String value, int position) {
        String raw = code != null ? code : value;
        String derived = NOT_CODE_CHAR
                .matcher(raw.toUpperCase(Locale.ROOT))
                .replaceAll("_")
                .replaceAll("^_+|_+$", "");
        if (derived.isEmpty()) {
            derived = "V" + position;
        }
        return derived.length() > 30 ? derived.substring(0, 30) : derived;
    }

    AttributeValue addValue(AttributeValueDraft draft, UUID valueId) {
        if (values.size() >= MAX_VALUES) {
            throw new ValidationFailure(List.of(
                    new FieldError("values", "MAX_ITEMS", "an attribute holds at most " + MAX_VALUES + " values")));
        }
        String valueCode = codeFor(draft.code(), draft.value(), values.size() + 1);
        if (findValueByCode(valueCode).isPresent()) {
            throw new BusinessRuleViolation(
                    CatalogErrorCode.ATTRIBUTE_VALUE_CODE_TAKEN, Map.of("attributeCode", code, "valueCode", valueCode));
        }
        int valueSort = draft.sort() != null ? draft.sort() : values.size() + 1;
        AttributeValue value = new AttributeValue(valueId, tenantId, valueCode, draft.value(), valueSort);
        values.add(value);
        return value;
    }

    void change(@Nullable String newName, @Nullable Integer newSort) {
        if (newName != null) {
            this.name = newName;
        }
        if (newSort != null) {
            this.sort = newSort;
        }
    }

    void changeValue(UUID valueId, @Nullable String newValue, @Nullable Integer newSort) {
        AttributeValue value = findValue(valueId)
                .orElseThrow(() -> new BusinessRuleViolation(
                        CatalogErrorCode.UNKNOWN_ATTRIBUTE_VALUE, Map.of("attributeCode", code, "valueId", valueId)));
        value.change(newValue != null ? newValue : value.value(), newSort != null ? newSort : value.sort());
    }

    public Optional<AttributeValue> findValue(UUID valueId) {
        return values.stream().filter(v -> v.id().equals(valueId)).findFirst();
    }

    public Optional<AttributeValue> findValueByCode(String valueCode) {
        String wanted = valueCode.toUpperCase(Locale.ROOT);
        return values.stream().filter(v -> v.code().equals(wanted)).findFirst();
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

    public int sort() {
        return sort;
    }

    /** Values in their display order. */
    public List<AttributeValue> values() {
        return values.stream()
                .sorted(Comparator.comparingInt(AttributeValue::sort).thenComparing(AttributeValue::code))
                .toList();
    }
}
