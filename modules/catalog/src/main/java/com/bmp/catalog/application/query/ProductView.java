package com.bmp.catalog.application.query;

import com.bmp.catalog.domain.model.Tracking;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A product as the API shows it: unit ids resolved to codes, exclusions as attribute and value codes; {@code variants}
 * is null unless they were asked for.
 */
public record ProductView(
        UUID id,
        String code,
        String name,
        @Nullable UUID categoryId,
        String template,
        String baseUnit,
        Tracking tracking,
        @Nullable UUID taxCategoryId,
        String propertiesJson,
        String status,
        List<AttributeTypeView> attributes,
        List<Map<String, String>> exclusions,
        List<Conversion> conversions,
        int variantCount,
        @Nullable List<VariantView> variants,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public ProductView {
        attributes = List.copyOf(attributes);
        exclusions = List.copyOf(exclusions);
        conversions = List.copyOf(conversions);
        variants = variants != null ? List.copyOf(variants) : null;
    }

    /** A product conversion with unit codes. */
    public record Conversion(String fromUnit, String toUnit, BigDecimal factor, String source) {}
}
