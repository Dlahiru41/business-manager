package com.bmp.catalog.application.command;

import com.bmp.catalog.domain.error.CatalogErrorCode;
import com.bmp.catalog.domain.model.Category;
import com.bmp.catalog.domain.model.Product;
import com.bmp.catalog.domain.model.TransformationDefinition;
import com.bmp.catalog.domain.model.Variant;
import com.bmp.catalog.domain.port.CategoryRepository;
import com.bmp.catalog.domain.port.ProductRepository;
import com.bmp.catalog.domain.port.TransformationDefinitionRepository;
import com.bmp.catalog.domain.port.VariantRepository;
import com.bmp.shared.error.NotFoundException;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Loads catalog aggregates of the current tenant, or answers 404 (also for other tenants' ids, 03 par. 9). */
@Component
public class CatalogLoader {

    private final ProductRepository products;
    private final VariantRepository variants;
    private final CategoryRepository categories;
    private final TransformationDefinitionRepository definitions;

    public CatalogLoader(
            ProductRepository products,
            VariantRepository variants,
            CategoryRepository categories,
            TransformationDefinitionRepository definitions) {
        this.products = products;
        this.variants = variants;
        this.categories = categories;
        this.definitions = definitions;
    }

    public Product product(UUID id) {
        return products.findById(id)
                .orElseThrow(() -> new NotFoundException(CatalogErrorCode.PRODUCT_NOT_FOUND, Map.of("productId", id)));
    }

    public Variant variant(UUID id) {
        return variants.findById(id)
                .orElseThrow(() -> new NotFoundException(CatalogErrorCode.VARIANT_NOT_FOUND, Map.of("variantId", id)));
    }

    /** A variant addressed under its product: a variant of another product is not found. */
    public Variant variantOf(UUID productId, UUID variantId) {
        Variant variant = variant(variantId);
        if (!variant.productId().value().equals(productId)) {
            throw new NotFoundException(CatalogErrorCode.VARIANT_NOT_FOUND, Map.of("variantId", variantId));
        }
        return variant;
    }

    /** The variant owning a packaging unit. */
    public Variant variantByUnit(UUID unitId) {
        return variants.findByPackagingUnit(unitId)
                .orElseThrow(() ->
                        new NotFoundException(CatalogErrorCode.PACKAGING_UNIT_NOT_FOUND, Map.of("unitId", unitId)));
    }

    public Category category(UUID id) {
        return categories
                .findById(id)
                .orElseThrow(
                        () -> new NotFoundException(CatalogErrorCode.CATEGORY_NOT_FOUND, Map.of("categoryId", id)));
    }

    public TransformationDefinition definition(UUID id) {
        return definitions
                .findById(id)
                .orElseThrow(() -> new NotFoundException(
                        CatalogErrorCode.TRANSFORMATION_DEFINITION_NOT_FOUND, Map.of("definitionId", id)));
    }
}
