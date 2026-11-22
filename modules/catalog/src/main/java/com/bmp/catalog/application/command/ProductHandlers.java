package com.bmp.catalog.application.command;

import com.bmp.catalog.application.CatalogEvents;
import com.bmp.catalog.application.CatalogViews;
import com.bmp.catalog.application.UnitDirectory;
import com.bmp.catalog.application.query.AttributeTypeView;
import com.bmp.catalog.application.query.ProductView;
import com.bmp.catalog.domain.model.AttributeDraft;
import com.bmp.catalog.domain.model.AttributeType;
import com.bmp.catalog.domain.model.AttributeValueChange;
import com.bmp.catalog.domain.model.Product;
import com.bmp.catalog.domain.model.Tracking;
import com.bmp.catalog.domain.model.Variant;
import com.bmp.catalog.domain.port.CatalogRevisions;
import com.bmp.catalog.domain.port.ProductRepository;
import com.bmp.catalog.domain.port.VariantRepository;
import com.bmp.shared.id.IdGenerator;
import com.bmp.web.etag.IfMatch;
import io.micrometer.observation.annotation.Observed;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changes products and their attribute types (CAT-05, CAT-11, CAT-13). Product data that variant snapshots carry (tax
 * category, tracking, attribute values, status) also publishes VariantUpdated or VariantDeactivated for its variants.
 */
@Service
public class ProductHandlers {

    private final CatalogLoader loader;
    private final ProductRepository products;
    private final VariantRepository variants;
    private final CatalogRevisions revisions;
    private final ProductReferences references;
    private final CatalogViews views;
    private final CatalogEvents events;
    private final IdGenerator ids;

    public ProductHandlers(
            CatalogLoader loader,
            ProductRepository products,
            VariantRepository variants,
            CatalogRevisions revisions,
            ProductReferences references,
            CatalogViews views,
            CatalogEvents events,
            IdGenerator ids) {
        this.loader = loader;
        this.products = products;
        this.variants = variants;
        this.revisions = revisions;
        this.references = references;
        this.views = views;
        this.events = events;
        this.ids = ids;
    }

    /**
     * PATCH semantics: {@code null} fields stay as they are.
     *
     * @param expectedVersion from {@code If-Match}
     * @param propertiesJson replaces all properties when given
     */
    public record UpdateProduct(
            UUID productId,
            long expectedVersion,
            @Nullable String name,
            @Nullable UUID categoryId,
            @Nullable UUID taxCategoryId,
            @Nullable Tracking tracking,
            @Nullable String propertiesJson) {}

    /** An attribute type with the version of its product (the ETag of attribute resources). */
    public record AttributeResult(AttributeTypeView attribute, long productVersion) {}

    @Transactional
    @Observed(name = "bmp.catalog.update-product")
    public ProductView update(UpdateProduct command) {
        Product product = loader.product(command.productId());
        IfMatch.check(command.expectedVersion(), product.version());
        if (command.name() != null) {
            product.rename(command.name());
        }
        if (command.categoryId() != null) {
            references.requireCategory(command.categoryId());
            product.changeCategory(command.categoryId());
        }
        boolean carriedByVariants = false;
        if (command.taxCategoryId() != null && !command.taxCategoryId().equals(product.taxCategoryId())) {
            references.requireTaxCategory(command.taxCategoryId());
            product.changeTaxCategory(command.taxCategoryId());
            carriedByVariants = true;
        }
        Tracking tracking = command.tracking();
        if (tracking != null && !tracking.equals(product.tracking())) {
            product.changeTracking(tracking);
            carriedByVariants = true;
        }
        if (command.propertiesJson() != null) {
            product.replaceProperties(command.propertiesJson());
        }
        return save(product, carriedByVariants);
    }

    /** Rule CAT-11: deactivates the product and every active variant; idempotent. */
    @Transactional
    @Observed(name = "bmp.catalog.deactivate-product")
    public ProductView deactivate(UUID productId) {
        Product product = loader.product(productId);
        List<Variant> active = variants.findByProduct(productId).stream()
                .filter(Variant::isActive)
                .toList();
        UnitDirectory directory = views.directory();
        if (!product.isActive() && active.isEmpty()) {
            return views.products(List.of(product), directory).getFirst();
        }
        long revision = revisions.next();
        product.deactivate();
        product.touch(revision);
        Product saved = products.saveAndFlush(product);
        for (Variant variant : active) {
            variant.deactivate();
            variant.touch(revision);
            events.variantDeactivated(variants.saveAndFlush(variant));
        }
        events.productUpdated(saved, directory);
        return views.products(List.of(saved), directory).getFirst();
    }

    /** Reactivates the product; its variants stay as they are. */
    @Transactional
    @Observed(name = "bmp.catalog.reactivate-product")
    public ProductView reactivate(UUID productId) {
        Product product = loader.product(productId);
        UnitDirectory directory = views.directory();
        if (product.isActive()) {
            return views.products(List.of(product), directory).getFirst();
        }
        product.reactivate();
        product.touch(revisions.next());
        Product saved = products.saveAndFlush(product);
        events.productUpdated(saved, directory);
        return views.products(List.of(saved), directory).getFirst();
    }

    /** Adds an attribute type; only while the product has no variants. */
    @Transactional
    @Observed(name = "bmp.catalog.create-attribute")
    public AttributeResult addAttribute(UUID productId, AttributeDraft draft) {
        Product product = loader.product(productId);
        AttributeType attribute = product.addAttribute(draft, variants.existsByProduct(productId), ids::newUuid);
        product.touch(revisions.next());
        Product saved = products.saveAndFlush(product);
        events.productUpdated(saved, views.directory());
        return new AttributeResult(AttributeTypeView.of(attribute), saved.version());
    }

    /** Renames or re-sorts an attribute type, changes or adds values; variants carrying it are re-published. */
    @Transactional
    @Observed(name = "bmp.catalog.update-attribute")
    public AttributeResult changeAttribute(
            UUID productId,
            UUID attributeId,
            long expectedVersion,
            @Nullable String name,
            @Nullable Integer sort,
            List<AttributeValueChange> values) {
        Product product = loader.product(productId);
        IfMatch.check(expectedVersion, product.version());
        product.changeAttribute(attributeId, name, sort, values, ids::newUuid);
        boolean renamesValues = values.stream().anyMatch(v -> v.id() != null);
        ProductView saved = save(product, renamesValues);
        Product reloaded = loader.product(productId);
        AttributeType attribute = reloaded.attribute(attributeId).orElseThrow();
        return new AttributeResult(AttributeTypeView.of(attribute), saved.version());
    }

    private ProductView save(Product product, boolean republishVariants) {
        long revision = revisions.next();
        product.touch(revision);
        Product saved = products.saveAndFlush(product);
        UnitDirectory directory = views.directory();
        if (republishVariants) {
            for (Variant variant : variants.findByProduct(saved.id().value())) {
                variant.touch(revision);
                events.variantUpdated(variants.saveAndFlush(variant), saved, directory);
            }
        }
        events.productUpdated(saved, directory);
        return Objects.requireNonNull(views.products(List.of(saved), directory).getFirst());
    }
}
