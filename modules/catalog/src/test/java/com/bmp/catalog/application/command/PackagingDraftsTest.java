package com.bmp.catalog.application.command;

import static com.bmp.catalog.domain.CatalogFixtures.KG;
import static com.bmp.catalog.domain.CatalogFixtures.ML;
import static com.bmp.catalog.domain.CatalogFixtures.PIECE;
import static com.bmp.catalog.domain.CatalogFixtures.TENANT;
import static com.bmp.catalog.domain.CatalogFixtures.UNITS;
import static com.bmp.catalog.domain.CatalogFixtures.assertCode;
import static com.bmp.catalog.domain.CatalogFixtures.ids;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.catalog.api.spi.TaxCategoryLookup;
import com.bmp.catalog.application.UnitDirectory;
import com.bmp.catalog.domain.CatalogFixtures;
import com.bmp.catalog.domain.error.CatalogErrorCode;
import com.bmp.catalog.domain.model.ConversionSource;
import com.bmp.catalog.domain.model.Dimension;
import com.bmp.catalog.domain.model.NewProduct;
import com.bmp.catalog.domain.model.PackagingUnitDraft;
import com.bmp.catalog.domain.model.Product;
import com.bmp.catalog.domain.model.ProductConversion;
import com.bmp.catalog.domain.model.ProductTemplate;
import com.bmp.catalog.domain.model.SellMode;
import com.bmp.catalog.domain.model.Tracking;
import com.bmp.catalog.domain.model.UnitOfMeasure;
import com.bmp.catalog.domain.model.Variant;
import com.bmp.catalog.domain.port.CategoryRepository;
import com.bmp.catalog.domain.port.PageKey;
import com.bmp.catalog.domain.port.UnitRepository;
import com.bmp.catalog.domain.service.ProductTemplateApplier;
import com.bmp.catalog.domain.service.UnitConverter;
import com.bmp.shared.error.ValidationFailure;
import com.bmp.shared.id.ProductId;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/** Packaging unit drafts from requests (CAT-02, CAT-03, CAT-04, CAT-09) and the product reference checks. */
class PackagingDraftsTest {

    private static final UnitDirectory DIRECTORY = UnitDirectory.load(new FixedUnits());

    private static PackagingDrafts drafts(
            ProductTemplate template, UnitOfMeasure base, ProductConversion... conversions) {
        return new PackagingDrafts(
                ProductTemplateApplier.strategy(template),
                base,
                DIRECTORY,
                new UnitConverter(UNITS, List.of(conversions)));
    }

    private static PackagingUnitSpec spec(
            String code, @Nullable String qty, PackagingUnitSpec.@Nullable DefinedAsSpec defined) {
        return new PackagingUnitSpec(
                code, code, qty != null ? new BigDecimal(qty) : null, defined, null, null, true, null);
    }

    @Test
    void cat02TheBaseUnitComesFirstAndARequestedUnitWithItsCodeConfiguresIt() {
        PackagingDrafts simple = drafts(ProductTemplate.SIMPLE, PIECE);
        PackagingUnitSpec piece =
                new PackagingUnitSpec("PIECE", "Single egg", BigDecimal.ONE, null, "DEFAULT", false, true, true);

        List<PackagingUnitDraft> all = simple.all(List.of(piece, spec("tray", "30", null)));

        assertThat(all).extracting(PackagingUnitDraft::code).containsExactly("piece", "tray");
        assertThat(all.getFirst().name()).isEqualTo("Single egg");
        assertThat(all.getFirst().canBuy()).isFalse();
        assertThat(all.getFirst().isDefaultSell()).isTrue();
        assertThat(simple.unitSpecs(List.of(piece)))
                .singleElement()
                .satisfies(u -> assertThat(u.canBuy()).isFalse());
        assertThat(simple.base(null).canBuy()).as("template default").isTrue();
        assertCode(
                () -> simple.base(spec("piece", null, new PackagingUnitSpec.DefinedAsSpec(BigDecimal.TEN, "dozen"))),
                CatalogErrorCode.INVALID_QTY_IN_BASE);
    }

    @Test
    void cat03Cat14SizesInAnotherUnitGoThroughTheConverter() {
        PackagingDrafts oil = drafts(
                ProductTemplate.BULK_BREAK,
                ML,
                new ProductConversion(KG.id(), ML.id(), new BigDecimal("1086.96"), ConversionSource.DENSITY));

        PackagingUnitDraft can = oil.other(new PackagingUnitSpec(
                "can",
                "Can",
                null,
                new PackagingUnitSpec.DefinedAsSpec(new BigDecimal("18"), "kg"),
                null,
                true,
                true,
                null));

        assertThat(can.qtyInBase()).isEqualByComparingTo("19565.28");
        assertThat(can.stockState()).isEqualTo("SEALED");
        assertThat(oil.base(null).stockState()).isEqualTo("OPEN");
        assertThat(oil.defined(null)).isNull();
        assertCode(
                () -> drafts(ProductTemplate.BULK_BREAK, ML)
                        .other(spec("can", null, new PackagingUnitSpec.DefinedAsSpec(BigDecimal.ONE, "kg"))),
                CatalogErrorCode.UNIT_NOT_CONVERTIBLE);
        assertCode(
                () -> oil.other(spec("x", null, new PackagingUnitSpec.DefinedAsSpec(BigDecimal.ONE, "furlong"))),
                CatalogErrorCode.UNKNOWN_UNIT);
    }

    @Test
    void cat04ANonBaseUnitHasExactlyOneSize() {
        PackagingDrafts simple = drafts(ProductTemplate.SIMPLE, PIECE);

        assertThatThrownBy(() -> simple.other(spec("box", null, null))).isInstanceOf(ValidationFailure.class);
        assertThatThrownBy(() ->
                        simple.other(spec("box", "6", new PackagingUnitSpec.DefinedAsSpec(BigDecimal.ONE, "dozen"))))
                .isInstanceOf(ValidationFailure.class);
        PackagingUnitDraft box = simple.other(
                new PackagingUnitSpec("box", "Box", new BigDecimal("6"), null, "DEFAULT", null, null, true));
        assertThat(box.canSell()).isFalse();
        assertThat(box.isDefaultSell()).isTrue();
    }

    @Test
    void siblingsCopyActiveUnitsAndAProductWithoutVariantsTakesTheTemplate() {
        Variant oil = CatalogFixtures.oil();
        UUID bottle = oil.packagingUnits().stream()
                .filter(u -> u.code().equals("bottle-750"))
                .findFirst()
                .orElseThrow()
                .id();
        oil.changePackagingUnit(
                bottle,
                new com.bmp.catalog.domain.model.PackagingUnitChange(
                        null, null, null, null, null, null, null, com.bmp.catalog.domain.model.CatalogStatus.INACTIVE));
        VariantShapes shapes = new VariantShapes();
        Product product = Product.create(
                ProductId.of(UUID.randomUUID()),
                TENANT,
                new NewProduct(
                        "OIL",
                        "Oil",
                        ProductTemplate.BULK_BREAK,
                        ML.id(),
                        null,
                        null,
                        Tracking.measured(),
                        "{}",
                        List.of()),
                ids());

        VariantFactory.Shape copied = shapes.forNewVariant(product, List.of(oil), DIRECTORY);
        VariantFactory.Shape fresh = shapes.forNewVariant(product, List.of(), DIRECTORY);
        VariantFactory.Shape requested = shapes.fromTemplate(
                product,
                List.of(spec("bottle-750", "750", null)),
                List.of("SEALED", "OPEN", "RESERVED"),
                SellMode.PREPACKED,
                DIRECTORY);

        assertThat(PackagingDrafts.copiesOf(oil)).hasSize(2);
        assertThat(copied.units()).extracting(PackagingUnitDraft::code).containsExactly("ml", "can");
        assertThat(copied.sellMode()).isEqualTo(SellMode.BREAK_ON_SALE);
        assertThat(fresh.units()).extracting(PackagingUnitDraft::code).containsExactly("ml");
        assertThat(fresh.stockStates()).containsExactly("SEALED", "OPEN");
        assertThat(requested.stockStates()).containsExactly("SEALED", "OPEN", "RESERVED");
        assertThat(requested.sellMode()).isEqualTo(SellMode.PREPACKED);
    }

    @Test
    void referencesAreCheckedThroughThePricingSpiWhenItExists() {
        UUID known = UUID.randomUUID();
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("taxCategories", (TaxCategoryLookup) known::equals);
        ProductReferences withPricing =
                new ProductReferences(new NoCategories(), beans.getBeanProvider(TaxCategoryLookup.class));
        ProductReferences withoutPricing = new ProductReferences(
                new NoCategories(), new DefaultListableBeanFactory().getBeanProvider(TaxCategoryLookup.class));

        withPricing.requireTaxCategory(known);
        withPricing.requireTaxCategory(null);
        withoutPricing.requireTaxCategory(UUID.randomUUID());
        withPricing.requireCategory(null);
        assertCode(() -> withPricing.requireTaxCategory(UUID.randomUUID()), CatalogErrorCode.UNKNOWN_TAX_CATEGORY);
        assertCode(() -> withPricing.requireCategory(UUID.randomUUID()), CatalogErrorCode.UNKNOWN_CATEGORY);
        assertThat(ProductReferences.conversions(
                        List.of(new ConversionSpec("KG", "ml", BigDecimal.TEN, ConversionSource.DENSITY)), DIRECTORY))
                .singleElement()
                .satisfies(c -> assertThat(c.fromUnitId()).isEqualTo(KG.id()));
        assertThat(DIRECTORY.byCode("Dozen").dimension()).isEqualTo(Dimension.COUNT);
        assertCode(() -> DIRECTORY.codeOf(UUID.randomUUID()), CatalogErrorCode.UNKNOWN_UNIT);
    }

    /** The fixture units, as the unit store would return them. */
    private static final class FixedUnits implements UnitRepository {
        @Override
        public List<UnitOfMeasure> findAll() {
            return UNITS;
        }

        @Override
        public Optional<UnitOfMeasure> findById(UUID id) {
            return UNITS.stream().filter(u -> u.id().equals(id)).findFirst();
        }

        @Override
        public Optional<UnitOfMeasure> findByCode(String code) {
            return UNITS.stream().filter(u -> u.code().equalsIgnoreCase(code)).findFirst();
        }

        @Override
        public List<UnitOfMeasure> findPage(@Nullable Dimension dimension, @Nullable PageKey after, int limit) {
            return UNITS;
        }

        @Override
        public void insert(UnitOfMeasure unit) {
            throw new UnsupportedOperationException();
        }
    }

    /** A category store without categories. */
    private static final class NoCategories implements CategoryRepository {
        @Override
        public Optional<com.bmp.catalog.domain.model.Category> findById(UUID id) {
            return Optional.empty();
        }

        @Override
        public com.bmp.catalog.domain.model.Category save(com.bmp.catalog.domain.model.Category category) {
            return category;
        }

        @Override
        public com.bmp.catalog.domain.model.Category saveAndFlush(com.bmp.catalog.domain.model.Category category) {
            return category;
        }

        @Override
        public List<com.bmp.catalog.domain.model.Category> findPage(
                @Nullable UUID parentId, @Nullable PageKey after, int limit) {
            return List.of();
        }

        @Override
        public int subtreeHeight(String path) {
            return 0;
        }

        @Override
        public void rebaseDescendants(String oldPath, String newPath) {}
    }
}
