package com.bmp.catalog.application.query;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** What a variant (re)generation created and deactivated, or would create in a dry run (CAT-05). */
public record GenerationView(boolean dryRun, List<Generated> created, List<Generated> deactivated, int unchanged) {

    public GenerationView {
        created = List.copyOf(created);
        deactivated = List.copyOf(deactivated);
    }

    /** One variant of the plan; the id is absent for variants a dry run did not create. */
    public record Generated(@Nullable UUID id, String sku, String name, Map<String, String> attributeValues) {
        public Generated {
            attributeValues = Map.copyOf(attributeValues);
        }
    }
}
