package com.bmp.catalog.application.command;

import com.bmp.catalog.application.CatalogEvents;
import com.bmp.catalog.application.CatalogViews;
import com.bmp.catalog.application.UnitDirectory;
import com.bmp.catalog.application.query.PackagingUnitView;
import com.bmp.catalog.domain.model.CatalogStatus;
import com.bmp.catalog.domain.model.DefinedQuantity;
import com.bmp.catalog.domain.model.PackagingUnit;
import com.bmp.catalog.domain.model.PackagingUnitChange;
import com.bmp.catalog.domain.model.Product;
import com.bmp.catalog.domain.model.Variant;
import com.bmp.catalog.domain.port.CatalogRevisions;
import com.bmp.catalog.domain.port.VariantRepository;
import com.bmp.web.etag.IfMatch;
import io.micrometer.observation.annotation.Observed;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adds and changes packaging units and their barcodes (CAT-04, CAT-07, CAT-12). Units are parts of their variant: every
 * change takes the variant's next version and publishes VariantUpdated (CAT-13). A resized unit resizes the
 * definitions sized by it (CAT-14).
 */
@Service
public class PackagingUnitHandlers {

    private final CatalogLoader loader;
    private final VariantRepository variants;
    private final CatalogRevisions revisions;
    private final VariantShapes shapes;
    private final DefinitionPublisher definitions;
    private final CatalogViews views;
    private final CatalogEvents events;

    public PackagingUnitHandlers(
            CatalogLoader loader,
            VariantRepository variants,
            CatalogRevisions revisions,
            VariantShapes shapes,
            DefinitionPublisher definitions,
            CatalogViews views,
            CatalogEvents events) {
        this.loader = loader;
        this.variants = variants;
        this.revisions = revisions;
        this.shapes = shapes;
        this.definitions = definitions;
        this.views = views;
        this.events = events;
    }

    /** A packaging unit with the version of its variant (the ETag of packaging unit resources). */
    public record UnitResult(PackagingUnitView unit, long variantVersion) {}

    /** PATCH semantics: {@code null} fields stay as they are; a new size is {@code qtyInBase} or {@code definedAs}. */
    public record UpdateUnit(
            @Nullable String name,
            @Nullable BigDecimal qtyInBase,
            PackagingUnitSpec.@Nullable DefinedAsSpec definedAs,
            @Nullable String stockState,
            @Nullable Boolean canBuy,
            @Nullable Boolean canSell,
            @Nullable Boolean isDefaultSell,
            @Nullable CatalogStatus status) {}

    @Transactional
    @Observed(name = "bmp.catalog.create-packaging-unit")
    public UnitResult add(UUID variantId, PackagingUnitSpec spec) {
        Variant variant = loader.variant(variantId);
        Product product = loader.product(variant.productId().value());
        UnitDirectory directory = views.directory();
        PackagingUnit unit =
                variant.addPackagingUnit(shapes.drafts(product, directory).other(spec), UUID.randomUUID());
        return saved(variant, product, unit.id(), directory);
    }

    @Transactional
    @Observed(name = "bmp.catalog.update-packaging-unit")
    public UnitResult update(UUID variantId, UUID unitId, long expectedVersion, UpdateUnit change) {
        Variant variant = loader.variant(variantId);
        IfMatch.check(expectedVersion, variant.version());
        Product product = loader.product(variant.productId().value());
        UnitDirectory directory = views.directory();
        PackagingDrafts drafts = shapes.drafts(product, directory);
        DefinedQuantity defined = drafts.defined(change.definedAs());
        BigDecimal size = defined != null ? drafts.toBase(defined) : change.qtyInBase();
        PackagingUnit unit = variant.changePackagingUnit(
                unitId,
                new PackagingUnitChange(
                        change.name(),
                        size,
                        defined,
                        change.stockState(),
                        change.canBuy(),
                        change.canSell(),
                        change.isDefaultSell(),
                        change.status()));
        UnitResult result = saved(variant, product, unit.id(), directory);
        if (size != null) {
            definitions.resize(Map.of(unit.id(), unit.qtyInBase()));
        }
        return result;
    }

    /** Rule CAT-12: a barcode maps to exactly one packaging unit of the tenant. */
    @Transactional
    @Observed(name = "bmp.catalog.add-barcode")
    public UnitResult addBarcode(UUID unitId, String barcode) {
        Variant variant = loader.variantByUnit(unitId);
        variant.addBarcode(unitId, barcode);
        return saved(variant, loader.product(variant.productId().value()), unitId, views.directory());
    }

    @Transactional
    @Observed(name = "bmp.catalog.remove-barcode")
    public UnitResult removeBarcode(UUID unitId, String barcode) {
        Variant variant = loader.variantByUnit(unitId);
        variant.removeBarcode(unitId, barcode);
        return saved(variant, loader.product(variant.productId().value()), unitId, views.directory());
    }

    private UnitResult saved(Variant variant, Product product, UUID unitId, UnitDirectory directory) {
        variant.touch(revisions.next());
        Variant saved = variants.saveAndFlush(variant);
        events.variantUpdated(saved, product, directory);
        PackagingUnit unit = saved.packagingUnit(unitId).orElseThrow();
        return new UnitResult(views.unit(saved, unit, directory), saved.version());
    }
}
