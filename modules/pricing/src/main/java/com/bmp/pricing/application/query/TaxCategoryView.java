package com.bmp.pricing.application.query;

import com.bmp.pricing.domain.model.TaxCategory;
import java.time.Instant;
import java.util.UUID;

/** A tax category as the API shows it. */
public record TaxCategoryView(UUID id, String code, String name, String status, long version, Instant createdAt) {

    public static TaxCategoryView of(TaxCategory category) {
        return new TaxCategoryView(
                category.id(),
                category.code(),
                category.name(),
                category.status().name(),
                category.version(),
                category.createdAt());
    }
}
