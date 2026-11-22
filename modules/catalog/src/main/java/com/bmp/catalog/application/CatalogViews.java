package com.bmp.catalog.application;

import com.bmp.catalog.application.query.AttributeTypeView;
import com.bmp.catalog.application.query.PackagingUnitView;
import com.bmp.catalog.application.query.ProductView;
import com.bmp.catalog.application.query.VariantView;
import com.bmp.catalog.domain.model.AttributeType;
import com.bmp.catalog.domain.model.AttributeValue;
import com.bmp.catalog.domain.model.DefinedQuantity;
import com.bmp.catalog.domain.model.Exclusion;
import com.bmp.catalog.domain.model.PackagingUnit;
import com.bmp.catalog.domain.model.Product;
import com.bmp.catalog.domain.model.Variant;
import com.bmp.catalog.domain.port.ProductRepository;
import com.bmp.catalog.domain.port.UnitRepository;
import com.bmp.catalog.domain.port.VariantRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Builds the read models of products and variants. Unit ids become codes and attribute value ids become codes and
 * values; lists load the products and variant counts they need in one query each (no N+1).
 */
@Component
public class CatalogViews {

    private final UnitRepository units;
    private final ProductRepository products;
    private final VariantRepository variants;

    public CatalogViews(UnitRepository units, ProductRepository products, VariantRepository variants) {
        this.units = units;
        this.products = products;
        this.variants = variants;
    }

    public UnitDirectory directory() {
        return UnitDirectory.load(units);
    }

    public ProductView product(
            Product product, UnitDirectory directory, int variantCount, @Nullable List<VariantView> variantViews) {
        return new ProductView(
                product.id().value(),
                product.code(),
                product.name(),
                product.categoryId(),
                product.template().name(),
                directory.codeOf(product.baseUnitId()),
                product.tracking(),
                product.taxCategoryId(),
                product.propertiesJson(),
                product.status().name(),
                product.axes().stream().map(AttributeTypeView::of).toList(),
                exclusions(product),
                product.conversions().stream()
                        .map(c -> new ProductView.Conversion(
                                directory.codeOf(c.fromUnitId()),
                                directory.codeOf(c.toUnitId()),
                                c.factor(),
                                c.source().name()))
                        .sorted(Comparator.comparing(ProductView.Conversion::fromUnit)
                                .thenComparing(ProductView.Conversion::toUnit))
                        .toList(),
                variantCount,
                variantViews,
                product.version(),
                product.createdAt(),
                product.updatedAt());
    }

    /** Products for a list, without variants; variant counts come from one grouped query. */
    public List<ProductView> products(List<Product> page, UnitDirectory directory) {
        Map<UUID, Long> counts =
                variants.countByProducts(page.stream().map(p -> p.id().value()).toList());
        return page.stream()
                .map(p -> product(
                        p, directory, counts.getOrDefault(p.id().value(), 0L).intValue(), null))
                .toList();
    }

    /** A product with all its variants, with or without their packaging units. */
    public ProductView productWithVariants(Product product, UnitDirectory directory, boolean withUnits) {
        List<VariantView> views = variants.findByProduct(product.id().value()).stream()
                .sorted(Comparator.comparing(Variant::sku))
                .map(v -> variant(v, product, directory, withUnits))
                .toList();
        return product(product, directory, views.size(), views);
    }

    public VariantView variant(Variant variant, Product product, UnitDirectory directory, boolean withUnits) {
        List<VariantView.AttributeValue> values = new ArrayList<>();
        for (AttributeValue value : product.valuesOf(variant.attributeValueIds())) {
            AttributeType attribute = product.attributeOf(value.id()).orElseThrow();
            values.add(new VariantView.AttributeValue(
                    attribute.id(), attribute.code(), value.id(), value.code(), value.value()));
        }
        return new VariantView(
                variant.id().value(),
                variant.productId().value(),
                variant.sku(),
                variant.name(),
                values,
                variant.stockStates(),
                variant.sellMode().name(),
                variant.status().name(),
                variant.isSellable(),
                variant.isPurchasable(),
                withUnits
                        ? variant.packagingUnits().stream()
                                .map(u -> unit(variant, u, directory))
                                .toList()
                        : null,
                variant.version(),
                variant.createdAt(),
                variant.updatedAt());
    }

    /** Variants of any products, with their packaging units; their products are loaded in one query. */
    public List<VariantView> variants(List<Variant> page, UnitDirectory directory) {
        Map<UUID, Product> owners =
                products
                        .findAllById(page.stream()
                                .map(v -> v.productId().value())
                                .distinct()
                                .toList())
                        .stream()
                        .collect(Collectors.toMap(p -> p.id().value(), Function.identity()));
        return page.stream()
                .map(v -> variant(v, owner(owners, v), directory, true))
                .toList();
    }

    public PackagingUnitView unit(Variant variant, PackagingUnit unit, UnitDirectory directory) {
        DefinedQuantity defined = unit.definedAs();
        return new PackagingUnitView(
                unit.id(),
                variant.id().value(),
                unit.code(),
                unit.name(),
                unit.qtyInBase(),
                defined != null
                        ? new PackagingUnitView.DefinedAs(defined.value(), directory.codeOf(defined.unitId()))
                        : null,
                unit.stockState(),
                unit.isBase(),
                unit.canBuy(),
                unit.canSell(),
                unit.isDefaultSell(),
                unit.status().name(),
                unit.barcodes());
    }

    /** The attribute and value codes of a combination, e.g. {@code {COLOUR=RED, SIZE=L}}. */
    public static Map<String, String> codes(Product product, java.util.Collection<UUID> valueIds) {
        Map<String, String> codes = new LinkedHashMap<>();
        for (AttributeValue value : product.valuesOf(valueIds)) {
            product.attributeOf(value.id()).ifPresent(attribute -> codes.put(attribute.code(), value.code()));
        }
        return codes;
    }

    private static List<Map<String, String>> exclusions(Product product) {
        return product.exclusions().stream()
                .map(Exclusion::valueIds)
                .map(ids -> codes(product, ids))
                .sorted(Comparator.comparing(Map::toString))
                .toList();
    }

    private static Product owner(Map<UUID, Product> owners, Variant variant) {
        return Optional.ofNullable(owners.get(variant.productId().value()))
                .orElseThrow(() -> new IllegalStateException("Variant " + variant.id() + " has no product"));
    }
}
