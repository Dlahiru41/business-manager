package com.bmp.catalog.application.command;

import com.bmp.catalog.application.CatalogEvents;
import com.bmp.catalog.application.CatalogViews;
import com.bmp.catalog.application.UnitDirectory;
import com.bmp.catalog.application.query.VariantView;
import com.bmp.catalog.domain.error.CatalogErrorCode;
import com.bmp.catalog.domain.model.Product;
import com.bmp.catalog.domain.model.SellMode;
import com.bmp.catalog.domain.model.Variant;
import com.bmp.catalog.domain.port.CatalogRevisions;
import com.bmp.catalog.domain.port.VariantRepository;
import com.bmp.catalog.domain.service.ProductTemplateApplier;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.web.etag.IfMatch;
import io.micrometer.observation.annotation.Observed;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Adds, changes, deactivates and reactivates single variants (CAT-05, CAT-06, CAT-07, CAT-11, CAT-13). */
@Service
public class VariantHandlers {

    private final CatalogLoader loader;
    private final VariantRepository variants;
    private final CatalogRevisions revisions;
    private final VariantFactory factory;
    private final VariantShapes shapes;
    private final DefinitionPublisher definitions;
    private final CatalogViews views;
    private final CatalogEvents events;

    public VariantHandlers(
            CatalogLoader loader,
            VariantRepository variants,
            CatalogRevisions revisions,
            VariantFactory factory,
            VariantShapes shapes,
            DefinitionPublisher definitions,
            CatalogViews views,
            CatalogEvents events) {
        this.loader = loader;
        this.variants = variants;
        this.revisions = revisions;
        this.factory = factory;
        this.shapes = shapes;
        this.definitions = definitions;
        this.views = views;
        this.events = events;
    }

    /**
     * A variant added by hand; absent values come from the oldest active variant or the template.
     *
     * @param attributeValues attribute code to value code; required exactly when the product has attributes
     */
    public record CreateVariant(
            UUID productId,
            @Nullable String name,
            @Nullable String sku,
            @Nullable Map<String, String> attributeValues,
            @Nullable List<String> stockStates,
            @Nullable SellMode sellMode,
            @Nullable List<PackagingUnitSpec> packagingUnits) {}

    /** PATCH semantics: {@code null} fields stay as they are. */
    public record UpdateVariant(
            UUID productId,
            UUID variantId,
            long expectedVersion,
            @Nullable String name,
            @Nullable String sku,
            @Nullable List<String> stockStates,
            @Nullable SellMode sellMode) {}

    @Transactional
    @Observed(name = "bmp.catalog.create-variant")
    public VariantView create(CreateVariant command) {
        Product product = loader.product(command.productId());
        if (!product.isActive()) {
            throw new BusinessRuleViolation(
                    CatalogErrorCode.PRODUCT_INACTIVE, Map.of("productId", command.productId()));
        }
        Map<String, String> codes = command.attributeValues() != null ? command.attributeValues() : Map.of();
        List<UUID> combination =
                product.axes().isEmpty() && codes.isEmpty() ? List.of() : product.resolveValues(codes, true);
        List<Variant> siblings = variants.findByProduct(command.productId());
        if (!combination.isEmpty()) {
            if (product.isExcluded(combination)) {
                throw new BusinessRuleViolation(
                        CatalogErrorCode.VARIANT_COMBINATION_EXCLUDED, Map.of("attributeValues", codes));
            }
            Set<UUID> wanted = Set.copyOf(combination);
            if (siblings.stream().anyMatch(v -> v.attributeValueIds().equals(wanted))) {
                throw new BusinessRuleViolation(
                        CatalogErrorCode.VARIANT_COMBINATION_EXISTS, Map.of("attributeValues", codes));
            }
        }
        UnitDirectory directory = views.directory();
        List<PackagingUnitSpec> units = command.packagingUnits();
        VariantFactory.Shape defaults = shapes.forNewVariant(product, siblings, directory);
        VariantFactory.Shape shape = units != null
                ? shapes.fromTemplate(product, units, command.stockStates(), command.sellMode(), directory)
                : new VariantFactory.Shape(
                        defaults.units(),
                        command.stockStates() != null ? command.stockStates() : defaults.stockStates(),
                        command.sellMode() != null ? command.sellMode() : defaults.sellMode());
        Variant created =
                factory.createOne(product, combination, command.sku(), command.name(), shape, revisions.next());
        definitions.createImplied(ProductTemplateApplier.strategy(product.template()), created);
        events.variantCreated(created, product, directory);
        return views.variant(created, product, directory, true);
    }

    @Transactional
    @Observed(name = "bmp.catalog.update-variant")
    public VariantView update(UpdateVariant command) {
        Variant variant = loader.variantOf(command.productId(), command.variantId());
        IfMatch.check(command.expectedVersion(), variant.version());
        if (command.name() != null) {
            variant.rename(command.name());
        }
        if (command.sku() != null) {
            variant.changeSku(command.sku());
        }
        if (command.stockStates() != null) {
            variant.changeStockStates(command.stockStates());
        }
        if (command.sellMode() != null) {
            variant.changeSellMode(command.sellMode());
        }
        return saved(variant, true);
    }

    /** Rule CAT-11: deactivated, never deleted; idempotent. */
    @Transactional
    @Observed(name = "bmp.catalog.deactivate-variant")
    public VariantView deactivate(UUID variantId) {
        Variant variant = loader.variant(variantId);
        if (!variant.deactivate()) {
            return view(variant);
        }
        variant.touch(revisions.next());
        Variant saved = variants.saveAndFlush(variant);
        events.variantDeactivated(saved);
        return view(saved);
    }

    /** Reactivates a variant of an active product; idempotent. */
    @Transactional
    @Observed(name = "bmp.catalog.reactivate-variant")
    public VariantView reactivate(UUID variantId) {
        Variant variant = loader.variant(variantId);
        Product product = loader.product(variant.productId().value());
        if (!product.isActive()) {
            throw new BusinessRuleViolation(
                    CatalogErrorCode.PRODUCT_INACTIVE,
                    Map.of("productId", product.id().value()));
        }
        if (!variant.reactivate()) {
            return view(variant);
        }
        return saved(variant, true);
    }

    private VariantView saved(Variant variant, boolean publish) {
        variant.touch(revisions.next());
        Variant saved = variants.saveAndFlush(variant);
        Product product = loader.product(saved.productId().value());
        UnitDirectory directory = views.directory();
        if (publish) {
            events.variantUpdated(saved, product, directory);
        }
        return views.variant(saved, product, directory, true);
    }

    private VariantView view(Variant variant) {
        return views.variant(variant, loader.product(variant.productId().value()), views.directory(), true);
    }
}
