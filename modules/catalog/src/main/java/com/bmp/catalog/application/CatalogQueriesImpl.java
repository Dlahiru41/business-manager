package com.bmp.catalog.application;

import com.bmp.catalog.api.CatalogQueries;
import com.bmp.catalog.api.dto.PackagingUnitInfo;
import com.bmp.catalog.api.dto.VariantInfo;
import com.bmp.catalog.domain.model.PackagingUnit;
import com.bmp.catalog.domain.model.Product;
import com.bmp.catalog.domain.model.Variant;
import com.bmp.catalog.domain.port.CatalogRevisions;
import com.bmp.catalog.domain.port.ProductRepository;
import com.bmp.catalog.domain.port.UnitRepository;
import com.bmp.catalog.domain.port.VariantRepository;
import com.bmp.shared.id.PackagingUnitId;
import com.bmp.shared.id.VariantId;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Implements the public read API of the catalog for other modules. */
@Component
class CatalogQueriesImpl implements CatalogQueries {

    private final VariantRepository variants;
    private final ProductRepository products;
    private final UnitRepository units;
    private final CatalogRevisions revisions;

    CatalogQueriesImpl(
            VariantRepository variants, ProductRepository products, UnitRepository units, CatalogRevisions revisions) {
        this.variants = variants;
        this.products = products;
        this.units = units;
        this.revisions = revisions;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<VariantInfo> findVariant(VariantId variant) {
        return variants.findById(variant.value()).map(this::info);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PackagingUnitInfo> findPackagingUnit(PackagingUnitId packagingUnit) {
        return variants.findByPackagingUnit(packagingUnit.value())
                .flatMap(v -> v.packagingUnit(packagingUnit.value()).map(u -> unit(v, u)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<VariantInfo> findByBarcode(String barcode) {
        return variants.findByBarcode(barcode).map(this::info);
    }

    @Override
    @Transactional(readOnly = true)
    public long catalogVersion() {
        return revisions.current();
    }

    private VariantInfo info(Variant variant) {
        Product product = products.findById(variant.productId().value()).orElseThrow();
        return new VariantInfo(
                variant.id(),
                variant.productId(),
                variant.sku(),
                variant.name(),
                UnitDirectory.load(units).codeOf(product.baseUnitId()),
                variant.stockStates(),
                variant.sellMode().name(),
                variant.isActive(),
                variant.isSellable(),
                variant.isPurchasable(),
                product.taxCategoryId(),
                variant.revision(),
                variant.packagingUnits().stream().map(u -> unit(variant, u)).toList());
    }

    private static PackagingUnitInfo unit(Variant variant, PackagingUnit unit) {
        return new PackagingUnitInfo(
                PackagingUnitId.of(unit.id()),
                variant.id(),
                unit.code(),
                unit.name(),
                unit.qtyInBase(),
                unit.stockState(),
                unit.isBase(),
                unit.canBuy(),
                unit.canSell(),
                unit.isDefaultSell(),
                unit.isActive(),
                unit.barcodes());
    }
}
