package com.bmp.catalog.application.query;

import com.bmp.catalog.domain.model.Category;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A category as the API shows it. */
public record CategoryView(
        UUID id,
        @Nullable UUID parentId,
        String name,
        List<UUID> ancestorIds,
        String status,
        long version,
        Instant createdAt) {

    public CategoryView {
        ancestorIds = List.copyOf(ancestorIds);
    }

    public static CategoryView of(Category category) {
        return new CategoryView(
                category.id(),
                category.parentId(),
                category.name(),
                category.ancestorIds(),
                category.status().name(),
                category.version(),
                category.createdAt());
    }
}
