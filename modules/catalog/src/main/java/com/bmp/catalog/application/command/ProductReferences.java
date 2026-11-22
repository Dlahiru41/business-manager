package com.bmp.catalog.application.command;

import com.bmp.catalog.api.spi.TaxCategoryLookup;
import com.bmp.catalog.application.UnitDirectory;
import com.bmp.catalog.domain.error.CatalogErrorCode;
import com.bmp.catalog.domain.model.ProductConversion;
import com.bmp.catalog.domain.port.CategoryRepository;
import com.bmp.shared.error.BusinessRuleViolation;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Checks what a product refers to: its category, its tax category (through the pricing SPI) and its units. */
@Component
class ProductReferences {

    private final CategoryRepository categories;
    private final ObjectProvider<TaxCategoryLookup> taxCategories;

    ProductReferences(CategoryRepository categories, ObjectProvider<TaxCategoryLookup> taxCategories) {
        this.categories = categories;
        this.taxCategories = taxCategories;
    }

    void requireCategory(@Nullable UUID categoryId) {
        if (categoryId != null && categories.findById(categoryId).isEmpty()) {
            throw new BusinessRuleViolation(CatalogErrorCode.UNKNOWN_CATEGORY, Map.of("categoryId", categoryId));
        }
    }

    /** Without the pricing module no tax category can exist, so every id is accepted then (01 par. 2 SPI default). */
    void requireTaxCategory(@Nullable UUID taxCategoryId) {
        TaxCategoryLookup lookup = taxCategories.getIfAvailable();
        if (taxCategoryId != null && lookup != null && !lookup.exists(taxCategoryId)) {
            throw new BusinessRuleViolation(
                    CatalogErrorCode.UNKNOWN_TAX_CATEGORY, Map.of("taxCategoryId", taxCategoryId));
        }
    }

    static List<ProductConversion> conversions(List<ConversionSpec> specs, UnitDirectory directory) {
        return specs.stream()
                .map(spec -> new ProductConversion(
                        directory.byCode(spec.fromUnit()).id(),
                        directory.byCode(spec.toUnit()).id(),
                        spec.factor(),
                        spec.source()))
                .toList();
    }
}
