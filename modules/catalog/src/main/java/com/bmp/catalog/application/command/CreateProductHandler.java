package com.bmp.catalog.application.command;

import com.bmp.catalog.application.CatalogEvents;
import com.bmp.catalog.application.CatalogViews;
import com.bmp.catalog.application.UnitDirectory;
import com.bmp.catalog.application.query.ProductView;
import com.bmp.catalog.domain.model.AttributeDraft;
import com.bmp.catalog.domain.model.Exclusion;
import com.bmp.catalog.domain.model.NewProduct;
import com.bmp.catalog.domain.model.Product;
import com.bmp.catalog.domain.model.ProductTemplate;
import com.bmp.catalog.domain.model.SellMode;
import com.bmp.catalog.domain.model.Tracking;
import com.bmp.catalog.domain.model.UnitOfMeasure;
import com.bmp.catalog.domain.model.Variant;
import com.bmp.catalog.domain.port.CatalogRevisions;
import com.bmp.catalog.domain.port.ProductRepository;
import com.bmp.catalog.domain.service.ProductTemplateApplier;
import com.bmp.catalog.domain.service.TemplateStrategy;
import com.bmp.catalog.domain.service.UnitConverter;
import com.bmp.catalog.domain.service.VariantMatrixGenerator;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.IdGenerator;
import com.bmp.shared.id.ProductId;
import io.micrometer.observation.annotation.Observed;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a product from a template in one call (CAT-02, CAT-05, CAT-06, CAT-09): its attributes and exclusions,
 * its variants with their base and requested packaging units, and the transformation definitions the template
 * implies, published. Publishes ProductCreated, VariantCreated and TransformationDefinitionPublished.
 */
@Service
public class CreateProductHandler {

    private final ProductRepository products;
    private final CatalogRevisions revisions;
    private final ProductReferences references;
    private final VariantFactory variants;
    private final DefinitionPublisher definitions;
    private final CatalogViews views;
    private final CatalogEvents events;
    private final IdGenerator ids;

    public CreateProductHandler(
            ProductRepository products,
            CatalogRevisions revisions,
            ProductReferences references,
            VariantFactory variants,
            DefinitionPublisher definitions,
            CatalogViews views,
            CatalogEvents events,
            IdGenerator ids) {
        this.products = products;
        this.revisions = revisions;
        this.references = references;
        this.variants = variants;
        this.definitions = definitions;
        this.views = views;
        this.events = events;
        this.ids = ids;
    }

    /**
     * A new product as the request describes it; absent values take the template defaults.
     *
     * @param propertiesJson free product facts as a JSON object
     * @param exclusions combinations never generated, as attribute code to value code
     */
    public record CreateProduct(
            String code,
            String name,
            ProductTemplate template,
            @Nullable UUID categoryId,
            @Nullable String baseUnit,
            @Nullable UUID taxCategoryId,
            @Nullable Tracking tracking,
            String propertiesJson,
            List<AttributeDraft> attributes,
            List<Map<String, String>> exclusions,
            @Nullable List<String> stockStates,
            @Nullable SellMode sellMode,
            List<ConversionSpec> conversions,
            List<PackagingUnitSpec> packagingUnits) {

        public CreateProduct {
            attributes = List.copyOf(attributes);
            exclusions = List.copyOf(exclusions);
            stockStates = stockStates != null ? List.copyOf(stockStates) : null;
            conversions = List.copyOf(conversions);
            packagingUnits = List.copyOf(packagingUnits);
        }
    }

    @Transactional
    @Observed(name = "bmp.catalog.create-product")
    public ProductView handle(CreateProduct command) {
        TemplateStrategy strategy = ProductTemplateApplier.strategy(command.template());
        ProductTemplateApplier.requireAttributes(
                command.template(), !command.attributes().isEmpty());
        references.requireCategory(command.categoryId());
        references.requireTaxCategory(command.taxCategoryId());
        UnitDirectory directory = views.directory();
        String baseCode = command.baseUnit();
        UnitOfMeasure baseUnit = directory.byCode(
                baseCode != null ? baseCode : strategy.defaults().baseUnitCode());
        Tracking tracking = command.tracking();

        Product product = Product.create(
                ids.next(ProductId::new),
                TenantContext.require(),
                new NewProduct(
                        command.code(),
                        command.name(),
                        command.template(),
                        baseUnit.id(),
                        command.categoryId(),
                        command.taxCategoryId(),
                        tracking != null ? tracking : strategy.defaults().tracking(),
                        command.propertiesJson(),
                        command.attributes()),
                ids::newUuid);
        product.replaceConversions(ProductReferences.conversions(command.conversions(), directory));
        product.replaceExclusions(command.exclusions().stream()
                .map(codes -> Exclusion.of(product.resolveValues(codes, false)))
                .toList());
        long revision = revisions.next();
        product.touch(revision);
        // Flushed first: variants refer to the product (and its attribute values) by key.
        Product saved = products.saveAndFlush(product);

        PackagingDrafts drafts = new PackagingDrafts(
                strategy, baseUnit, directory, new UnitConverter(directory.all(), saved.conversions()));
        List<String> requestedStates = command.stockStates();
        List<String> states = requestedStates != null
                ? requestedStates
                : strategy.stockStates(drafts.unitSpecs(command.packagingUnits()));
        SellMode requestedMode = command.sellMode();
        VariantFactory.Shape shape = new VariantFactory.Shape(
                drafts.all(command.packagingUnits()),
                states,
                requestedMode != null ? requestedMode : strategy.defaults().sellMode());
        List<Variant> created = variants.create(saved, combinations(saved), shape, revision);
        definitions.createImplied(strategy, created);

        events.productCreated(saved, directory);
        created.forEach(variant -> events.variantCreated(variant, saved, directory));
        return views.productWithVariants(saved, directory, true);
    }

    /** Rule CAT-05: the matrix of a product with attributes; one variant without attributes otherwise. */
    private static List<List<UUID>> combinations(Product product) {
        if (product.axes().isEmpty()) {
            return List.of(List.of());
        }
        return VariantMatrixGenerator.plan(axes(product), product::isExcluded, List.of())
                .toCreate();
    }

    /** The attribute axes of a product in matrix order. */
    static List<VariantMatrixGenerator.Axis> axes(Product product) {
        return product.axes().stream()
                .map(a -> new VariantMatrixGenerator.Axis(
                        a.id(), a.values().stream().map(v -> v.id()).toList()))
                .toList();
    }
}
