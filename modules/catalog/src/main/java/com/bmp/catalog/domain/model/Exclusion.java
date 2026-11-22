package com.bmp.catalog.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Attribute values never combined into a variant (CAT-05): a combination is excluded when it contains every value of
 * an exclusion, so {@code {Golden Yellow, XS}} removes exactly that egg, and {@code {Golden Yellow}} would remove every
 * golden yellow size.
 *
 * @param valueIds the attribute value ids, kept sorted so equal sets are equal exclusions
 */
@Embeddable
public record Exclusion(
        @Column(name = "attribute_value_ids", nullable = false) @JdbcTypeCode(SqlTypes.ARRAY)
        List<UUID> valueIds) {

    public Exclusion {
        Objects.requireNonNull(valueIds, "valueIds");
        if (valueIds.isEmpty()) {
            throw new IllegalArgumentException("An exclusion names at least one attribute value");
        }
        valueIds = valueIds.stream().distinct().sorted().toList();
    }

    public static Exclusion of(Collection<UUID> valueIds) {
        return new Exclusion(List.copyOf(valueIds));
    }

    /** Whether the combination of attribute values falls under this exclusion. */
    public boolean excludes(Collection<UUID> combination) {
        return combination.containsAll(valueIds);
    }
}
