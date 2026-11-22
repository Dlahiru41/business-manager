package com.bmp.catalog.application.command;

import com.bmp.catalog.application.CatalogEvents;
import com.bmp.catalog.application.CatalogViews;
import com.bmp.catalog.application.UnitDirectory;
import com.bmp.catalog.application.query.GenerationView;
import com.bmp.catalog.domain.error.CatalogErrorCode;
import com.bmp.catalog.domain.model.Exclusion;
import com.bmp.catalog.domain.model.Product;
import com.bmp.catalog.domain.model.Variant;
import com.bmp.catalog.domain.port.CatalogRevisions;
import com.bmp.catalog.domain.port.ProductRepository;
import com.bmp.catalog.domain.port.VariantRepository;
import com.bmp.catalog.domain.service.VariantMatrixGenerator;
import com.bmp.catalog.domain.service.VariantMatrixGenerator.Existing;
import com.bmp.catalog.domain.service.VariantMatrixGenerator.Plan;
import com.bmp.shared.error.BusinessRuleViolation;
import io.micrometer.observation.annotation.Observed;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regenerates a product's variant matrix (CAT-05): optional new exclusions, missing combinations created with the
 * packaging units of the oldest active variant, newly excluded active variants deactivated, nothing deleted. A dry
 * run computes the same plan and changes nothing.
 */
@Service
public class GenerateVariantsHandler {

    private final CatalogLoader loader;
    private final ProductRepository products;
    private final VariantRepository variants;
    private final CatalogRevisions revisions;
    private final VariantFactory factory;
    private final VariantShapes shapes;
    private final CatalogViews views;
    private final CatalogEvents events;

    public GenerateVariantsHandler(
            CatalogLoader loader,
            ProductRepository products,
            VariantRepository variants,
            CatalogRevisions revisions,
            VariantFactory factory,
            VariantShapes shapes,
            CatalogViews views,
            CatalogEvents events) {
        this.loader = loader;
        this.products = products;
        this.variants = variants;
        this.revisions = revisions;
        this.factory = factory;
        this.shapes = shapes;
        this.views = views;
        this.events = events;
    }

    /**
     * Plans the regeneration and, unless it is a dry run, carries it out.
     *
     * @param exclusions the new exclusions (attribute code to value code), or {@code null} to keep the current ones
     */
    @Transactional
    @Observed(name = "bmp.catalog.generate-variants")
    public GenerationView handle(UUID productId, @Nullable List<Map<String, String>> exclusions, boolean dryRun) {
        Product product = loader.product(productId);
        if (!product.isActive()) {
            throw new BusinessRuleViolation(CatalogErrorCode.PRODUCT_INACTIVE, Map.of("productId", productId));
        }
        if (product.axes().isEmpty()) {
            throw new BusinessRuleViolation(
                    CatalogErrorCode.ATTRIBUTES_REQUIRED, Map.of("template", product.template()));
        }
        List<Exclusion> wanted = exclusions != null
                ? exclusions.stream()
                        .map(codes -> Exclusion.of(product.resolveValues(codes, false)))
                        .toList()
                : List.copyOf(product.exclusions());
        List<Variant> existing = variants.findByProduct(productId);
        Plan plan = VariantMatrixGenerator.plan(
                CreateProductHandler.axes(product),
                combination -> wanted.stream().anyMatch(e -> e.excludes(combination)),
                existing.stream()
                        .map(v -> new Existing(v.id().value(), v.attributeValueIds(), v.isActive()))
                        .toList());
        List<GenerationView.Generated> deactivated = existing.stream()
                .filter(v -> plan.toDeactivate().contains(v.id().value()))
                .map(v -> generated(product, v))
                .toList();
        if (dryRun) {
            Set<String> taken = new HashSet<>(variants.skusStartingWith(product.code()));
            List<GenerationView.Generated> created = new ArrayList<>();
            for (List<UUID> combination : plan.toCreate()) {
                String sku = factory.skuFor(product, combination, taken);
                taken.add(sku);
                created.add(new GenerationView.Generated(
                        null,
                        sku,
                        VariantFactory.nameFor(product, combination),
                        CatalogViews.codes(product, combination)));
            }
            return new GenerationView(true, created, deactivated, plan.unchanged());
        }

        long revision = revisions.next();
        if (exclusions != null) {
            product.replaceExclusions(wanted);
        }
        product.touch(revision);
        Product saved = products.saveAndFlush(product);
        UnitDirectory directory = views.directory();
        List<Variant> created = plan.toCreate().isEmpty()
                ? List.of()
                : factory.create(saved, plan.toCreate(), shapes.forNewVariant(saved, existing, directory), revision);
        for (Variant variant : existing) {
            if (plan.toDeactivate().contains(variant.id().value())) {
                variant.deactivate();
                variant.touch(revision);
                events.variantDeactivated(variants.saveAndFlush(variant));
            }
        }
        created.forEach(variant -> events.variantCreated(variant, saved, directory));
        events.productUpdated(saved, directory);
        return new GenerationView(
                false, created.stream().map(v -> generated(saved, v)).toList(), deactivated, plan.unchanged());
    }

    private static GenerationView.Generated generated(Product product, Variant variant) {
        return new GenerationView.Generated(
                variant.id().value(),
                variant.sku(),
                variant.name(),
                CatalogViews.codes(product, variant.attributeValueIds()));
    }
}
