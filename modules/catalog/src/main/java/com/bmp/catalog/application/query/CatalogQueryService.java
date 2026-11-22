package com.bmp.catalog.application.query;

import com.bmp.catalog.application.CatalogViews;
import com.bmp.catalog.application.UnitDirectory;
import com.bmp.catalog.application.command.CatalogLoader;
import com.bmp.catalog.domain.error.CatalogErrorCode;
import com.bmp.catalog.domain.model.AttributeType;
import com.bmp.catalog.domain.model.CatalogStatus;
import com.bmp.catalog.domain.model.DefinitionStatus;
import com.bmp.catalog.domain.model.Dimension;
import com.bmp.catalog.domain.model.PackagingUnit;
import com.bmp.catalog.domain.model.Product;
import com.bmp.catalog.domain.model.ProductTemplate;
import com.bmp.catalog.domain.model.TransformationKind;
import com.bmp.catalog.domain.model.UnitOfMeasure;
import com.bmp.catalog.domain.model.Variant;
import com.bmp.catalog.domain.port.CatalogRevisions;
import com.bmp.catalog.domain.port.CategoryRepository;
import com.bmp.catalog.domain.port.PageKey;
import com.bmp.catalog.domain.port.ProductRepository;
import com.bmp.catalog.domain.port.TransformationDefinitionRepository;
import com.bmp.catalog.domain.port.UnitRepository;
import com.bmp.catalog.domain.port.VariantRepository;
import com.bmp.catalog.domain.service.ProductTemplateApplier;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.NotFoundException;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.web.pagination.CursorCodec;
import com.bmp.web.pagination.PageQuery;
import com.bmp.web.pagination.PageResponse;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of the catalog: pages, single resources, lookups and the till snapshot (CAT-08, CAT-12, CAT-13). */
@Service
public class CatalogQueryService {

    private static final int CURSOR_VALUES = 2;

    private final UnitRepository units;
    private final CategoryRepository categories;
    private final ProductRepository products;
    private final VariantRepository variants;
    private final TransformationDefinitionRepository definitions;
    private final CatalogRevisions revisions;
    private final CatalogLoader loader;
    private final CatalogViews views;

    public CatalogQueryService(
            UnitRepository units,
            CategoryRepository categories,
            ProductRepository products,
            VariantRepository variants,
            TransformationDefinitionRepository definitions,
            CatalogRevisions revisions,
            CatalogLoader loader,
            CatalogViews views) {
        this.units = units;
        this.categories = categories;
        this.products = products;
        this.variants = variants;
        this.definitions = definitions;
        this.revisions = revisions;
        this.loader = loader;
        this.views = views;
    }

    /** An attribute type with the version of its product. */
    public record AttributeResult(AttributeTypeView attribute, long productVersion) {}

    /** A packaging unit with the version of its variant. */
    public record UnitResult(PackagingUnitView unit, long variantVersion) {}

    /** A lookup hit: the variant and, for a barcode, the packaging unit it maps to. */
    public record LookupResult(
            VariantView variant, @Nullable UUID packagingUnitId) {}

    public List<TemplateView> templates() {
        return ProductTemplateApplier.all().stream().map(TemplateView::of).toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<UnitView> units(@Nullable Dimension dimension, PageQuery query) {
        List<UnitView> page = units.findPage(dimension, after(query), query.fetchSize()).stream()
                .map(UnitView::of)
                .toList();
        return PageResponse.of(page, query, u -> List.of(unitKey(u), u.id().toString()));
    }

    @Transactional(readOnly = true)
    public PageResponse<CategoryView> categories(@Nullable UUID parentId, PageQuery query) {
        List<CategoryView> page = categories.findPage(parentId, after(query), query.fetchSize()).stream()
                .map(CategoryView::of)
                .toList();
        return PageResponse.of(page, query, c -> List.of(c.name(), c.id().toString()));
    }

    @Transactional(readOnly = true)
    public CategoryView category(UUID categoryId) {
        return CategoryView.of(loader.category(categoryId));
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductView> products(
            @Nullable String q,
            @Nullable UUID categoryId,
            @Nullable CatalogStatus status,
            @Nullable ProductTemplate template,
            PageQuery query) {
        List<Product> page = products.findPage(
                new ProductRepository.Filter(q, categoryId, status, template), after(query), query.fetchSize());
        return PageResponse.of(
                views.products(page, views.directory()), query, p -> List.of(p.code(), p.id().toString()));
    }

    /** A product; with variants when asked, with their packaging units when {@code withUnits}. */
    @Transactional(readOnly = true)
    public ProductView product(UUID productId, boolean withVariants, boolean withUnits) {
        Product product = loader.product(productId);
        UnitDirectory directory = views.directory();
        if (withVariants || withUnits) {
            ProductView view = views.productWithVariants(product, directory, withUnits);
            return view;
        }
        return views.products(List.of(product), directory).getFirst();
    }

    @Transactional(readOnly = true)
    public List<AttributeTypeView> attributes(UUID productId) {
        return loader.product(productId).axes().stream()
                .map(AttributeTypeView::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public AttributeResult attribute(UUID productId, UUID attributeId) {
        Product product = loader.product(productId);
        AttributeType attribute = product.attribute(attributeId)
                .orElseThrow(() -> new NotFoundException(
                        CatalogErrorCode.ATTRIBUTE_NOT_FOUND, Map.of("attributeId", attributeId)));
        return new AttributeResult(AttributeTypeView.of(attribute), product.version());
    }

    @Transactional(readOnly = true)
    public PageResponse<VariantView> productVariants(UUID productId, PageQuery query) {
        loader.product(productId);
        return variants(new VariantRepository.Filter(null, null, null, productId, null), query);
    }

    @Transactional(readOnly = true)
    public VariantView variant(UUID productId, UUID variantId) {
        Variant variant = loader.variantOf(productId, variantId);
        return views.variant(variant, loader.product(productId), views.directory(), true);
    }

    @Transactional(readOnly = true)
    public List<PackagingUnitView> packagingUnits(UUID variantId) {
        Variant variant = loader.variant(variantId);
        UnitDirectory directory = views.directory();
        return variant.packagingUnits().stream()
                .map(u -> views.unit(variant, u, directory))
                .toList();
    }

    @Transactional(readOnly = true)
    public UnitResult packagingUnit(UUID variantId, UUID unitId) {
        Variant variant = loader.variant(variantId);
        PackagingUnit unit = variant.packagingUnit(unitId)
                .orElseThrow(() ->
                        new NotFoundException(CatalogErrorCode.PACKAGING_UNIT_NOT_FOUND, Map.of("unitId", unitId)));
        return new UnitResult(views.unit(variant, unit, views.directory()), variant.version());
    }

    /** Rule CAT-12: by barcode (with the unit it maps to) or by SKU; exactly one of them. */
    @Transactional(readOnly = true)
    public LookupResult lookup(@Nullable String barcode, @Nullable String sku) {
        if ((barcode == null) == (sku == null)) {
            throw new BusinessRuleViolation(
                    PlatformErrorCode.INVALID_PARAMETER,
                    "Give exactly one of barcode and sku",
                    Map.of("parameter", "barcode"));
        }
        Optional<Variant> found =
                barcode != null ? variants.findByBarcode(barcode) : variants.findBySku(sku.toUpperCase(Locale.ROOT));
        Variant variant = found.orElseThrow(() -> new NotFoundException(CatalogErrorCode.VARIANT_NOT_FOUND, Map.of()));
        UUID unitId = barcode == null
                ? null
                : variant.packagingUnits().stream()
                        .filter(u -> u.barcodes().contains(barcode))
                        .map(PackagingUnit::id)
                        .findFirst()
                        .orElse(null);
        VariantView view =
                views.variant(variant, loader.product(variant.productId().value()), views.directory(), true);
        return new LookupResult(view, unitId);
    }

    /** Rule CAT-08: variants across products, filtered; {@code null} filters match everything. */
    @Transactional(readOnly = true)
    public PageResponse<VariantView> variants(
            @Nullable Boolean sellable,
            @Nullable Boolean purchasable,
            @Nullable String q,
            @Nullable UUID productId,
            @Nullable CatalogStatus status,
            PageQuery query) {
        return variants(new VariantRepository.Filter(sellable, purchasable, q, productId, status), query);
    }

    @Transactional(readOnly = true)
    PageResponse<VariantView> variants(VariantRepository.Filter filter, PageQuery query) {
        List<Variant> page = variants.findPage(filter, after(query), query.fetchSize());
        return PageResponse.of(
                views.variants(page, views.directory()), query, v -> List.of(v.sku(), v.id().toString()));
    }

    @Transactional(readOnly = true)
    public PageResponse<DefinitionView> definitions(
            @Nullable TransformationKind kind,
            @Nullable DefinitionStatus status,
            @Nullable UUID variantId,
            PageQuery query) {
        List<DefinitionView> page =
                definitions.findPage(kind, status, variantId, after(query), query.fetchSize()).stream()
                        .map(DefinitionView::of)
                        .toList();
        return PageResponse.of(page, query, d -> List.of(d.code(), d.id().toString()));
    }

    @Transactional(readOnly = true)
    public DefinitionView definition(UUID definitionId) {
        return DefinitionView.of(loader.definition(definitionId));
    }

    /** The tenant's catalog version (the ETag of snapshots). */
    @Transactional(readOnly = true)
    public long catalogVersion() {
        return revisions.current();
    }

    /**
     * The whole catalog, or the products and variants changed after {@code sinceVersion} (CAT-13). Inactive records
     * are included, so a till can retire them.
     */
    @Transactional(readOnly = true)
    public SnapshotView snapshot(@Nullable Long sinceVersion) {
        long version = revisions.current();
        long since = sinceVersion != null ? sinceVersion : 0;
        UnitDirectory directory = views.directory();
        List<UnitView> allUnits = directory.all().stream()
                .sorted(Comparator.comparing(UnitOfMeasure::isStandard).reversed())
                .map(UnitView::of)
                .toList();
        List<ProductView> changedProducts = views.products(products.findChangedSince(since), directory);
        List<VariantView> changedVariants = views.variants(variants.findChangedSince(since), directory);
        return new SnapshotView(
                version, sinceVersion, sinceVersion == null, allUnits, changedProducts, changedVariants);
    }

    private static @Nullable PageKey after(PageQuery query) {
        String cursor = query.cursor();
        if (cursor == null) {
            return null;
        }
        List<String> values = CursorCodec.decode(cursor, CURSOR_VALUES);
        try {
            return new PageKey(values.get(0), UUID.fromString(values.get(1)));
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleViolation(
                    PlatformErrorCode.INVALID_PARAMETER, "The cursor is invalid", Map.of("parameter", "cursor"));
        }
    }

    private static String unitKey(UnitView unit) {
        return (unit.standard() ? "0:" : "1:") + unit.code().toLowerCase(Locale.ROOT);
    }
}
