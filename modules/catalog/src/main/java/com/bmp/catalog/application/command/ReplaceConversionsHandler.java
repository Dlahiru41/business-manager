package com.bmp.catalog.application.command;

import com.bmp.catalog.application.CatalogEvents;
import com.bmp.catalog.application.CatalogViews;
import com.bmp.catalog.application.UnitDirectory;
import com.bmp.catalog.application.query.ProductView;
import com.bmp.catalog.domain.model.Product;
import com.bmp.catalog.domain.model.Variant;
import com.bmp.catalog.domain.port.CatalogRevisions;
import com.bmp.catalog.domain.port.ProductRepository;
import com.bmp.catalog.domain.port.VariantRepository;
import com.bmp.catalog.domain.service.UnitConverter;
import com.bmp.web.etag.IfMatch;
import io.micrometer.observation.annotation.Observed;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replaces a product's conversions (CAT-03, CAT-14), e.g. the measured yield of a can of oil. Packaging units defined
 * in another unit are resized; this changes future conversions only (stock already in base units is untouched).
 * Resized variants publish VariantUpdated; published definitions sized by a resized unit get a new version.
 */
@Service
public class ReplaceConversionsHandler {

    private final CatalogLoader loader;
    private final ProductRepository products;
    private final VariantRepository variants;
    private final CatalogRevisions revisions;
    private final DefinitionPublisher definitions;
    private final CatalogViews views;
    private final CatalogEvents events;

    public ReplaceConversionsHandler(
            CatalogLoader loader,
            ProductRepository products,
            VariantRepository variants,
            CatalogRevisions revisions,
            DefinitionPublisher definitions,
            CatalogViews views,
            CatalogEvents events) {
        this.loader = loader;
        this.products = products;
        this.variants = variants;
        this.revisions = revisions;
        this.definitions = definitions;
        this.views = views;
        this.events = events;
    }

    @Transactional
    @Observed(name = "bmp.catalog.replace-conversions")
    public ProductView handle(UUID productId, long expectedVersion, List<ConversionSpec> conversions) {
        Product product = loader.product(productId);
        IfMatch.check(expectedVersion, product.version());
        UnitDirectory directory = views.directory();
        product.replaceConversions(ProductReferences.conversions(conversions, directory));
        UnitConverter converter = new UnitConverter(directory.all(), product.conversions());
        long revision = revisions.next();
        product.touch(revision);
        Product saved = products.saveAndFlush(product);

        Map<UUID, BigDecimal> resized = new HashMap<>();
        for (Variant variant : variants.findByProduct(productId)) {
            Map<UUID, BigDecimal> changed = variant.resizeDefinedUnits(
                    defined -> converter.convert(defined.value(), defined.unitId(), saved.baseUnitId()));
            if (!changed.isEmpty()) {
                resized.putAll(changed);
                variant.touch(revision);
                events.variantUpdated(variants.saveAndFlush(variant), saved, directory);
            }
        }
        if (!resized.isEmpty()) {
            definitions.resize(resized);
        }
        events.productUpdated(saved, directory);
        return views.products(List.of(saved), directory).getFirst();
    }
}
