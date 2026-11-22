package com.bmp.catalog.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a new product is made of, with template defaults already applied.
 *
 * @param code unique per tenant, the SKU prefix; stored upper-case
 * @param name display name
 * @param template the template it was created from
 * @param baseUnitId the unit all its stock is kept in
 * @param categoryId optional category
 * @param taxCategoryId optional tax category of the pricing module
 * @param tracking what its stock tracks
 * @param propertiesJson free product facts as a JSON object
 * @param attributes its attribute types with values
 */
public record NewProduct(
        String code,
        String name,
        ProductTemplate template,
        UUID baseUnitId,
        @Nullable UUID categoryId,
        @Nullable UUID taxCategoryId,
        Tracking tracking,
        String propertiesJson,
        List<AttributeDraft> attributes) {

    public NewProduct {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(baseUnitId, "baseUnitId");
        Objects.requireNonNull(tracking, "tracking");
        Objects.requireNonNull(propertiesJson, "propertiesJson");
        attributes = List.copyOf(attributes);
    }
}
