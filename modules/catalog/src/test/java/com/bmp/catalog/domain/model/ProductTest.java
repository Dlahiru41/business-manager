package com.bmp.catalog.domain.model;

import static com.bmp.catalog.domain.CatalogFixtures.KG;
import static com.bmp.catalog.domain.CatalogFixtures.ML;
import static com.bmp.catalog.domain.CatalogFixtures.PIECE;
import static com.bmp.catalog.domain.CatalogFixtures.TENANT;
import static com.bmp.catalog.domain.CatalogFixtures.assertCode;
import static com.bmp.catalog.domain.CatalogFixtures.ids;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.catalog.domain.error.CatalogErrorCode;
import com.bmp.shared.error.ValidationFailure;
import com.bmp.shared.id.ProductId;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductTest {

    private static AttributeDraft colour() {
        return new AttributeDraft(
                "colour",
                "Colour",
                1,
                List.of(
                        new AttributeValueDraft("RED", "Red", null),
                        new AttributeValueDraft(null, "White", null),
                        new AttributeValueDraft(null, "Golden Yellow", null)));
    }

    private static AttributeDraft size() {
        return new AttributeDraft(
                "SIZE",
                "Size",
                2,
                List.of(new AttributeValueDraft("XS", "XS", null), new AttributeValueDraft("L", "L", null)));
    }

    private static Product eggs() {
        return Product.create(
                ProductId.of(UUID.randomUUID()),
                TENANT,
                new NewProduct(
                        "egg",
                        "Eggs",
                        ProductTemplate.VARIANT_MATRIX,
                        PIECE.id(),
                        null,
                        null,
                        Tracking.counted(),
                        "{}",
                        List.of(size(), colour())),
                ids());
    }

    @Test
    void shouldCreateAProductWithItsAttributesInMatrixOrder() {
        Product eggs = eggs();

        assertThat(eggs.code()).isEqualTo("EGG");
        assertThat(eggs.isActive()).isTrue();
        assertThat(eggs.axes()).extracting(AttributeType::code).containsExactly("COLOUR", "SIZE");
        assertThat(eggs.axes().getFirst().values())
                .extracting(AttributeValue::code)
                .as("value codes are derived from the values when absent")
                .containsExactly("RED", "WHITE", "GOLDEN_YELLOW");
    }

    @Test
    void cat05ResolvesCombinationsAndExclusionsByCode() {
        Product eggs = eggs();

        List<UUID> redXs = eggs.resolveValues(Map.of("colour", "RED", "SIZE", "xs"), true);
        List<UUID> golden = eggs.resolveValues(Map.of("COLOUR", "GOLDEN_YELLOW"), false);

        assertThat(eggs.valuesOf(redXs)).extracting(AttributeValue::code).containsExactly("RED", "XS");
        assertThat(eggs.attributeOf(redXs.getFirst()).orElseThrow().code()).isEqualTo("COLOUR");
        eggs.replaceExclusions(List.of(Exclusion.of(golden)));
        assertThat(eggs.isExcluded(eggs.resolveValues(Map.of("COLOUR", "GOLDEN_YELLOW", "SIZE", "L"), true)))
                .isTrue();
        assertThat(eggs.isExcluded(redXs)).isFalse();
        assertCode(() -> eggs.resolveValues(Map.of("COLOUR", "RED"), true), CatalogErrorCode.UNKNOWN_ATTRIBUTE_VALUE);
        assertCode(
                () -> eggs.resolveValues(Map.of("COLOUR", "BLUE", "SIZE", "L"), true),
                CatalogErrorCode.UNKNOWN_ATTRIBUTE_VALUE);
        assertCode(
                () -> eggs.resolveValues(Map.of("COLOUR", "RED", "SIZE", "L", "GRADE", "A"), true),
                CatalogErrorCode.UNKNOWN_ATTRIBUTE_VALUE);
        assertCode(() -> eggs.resolveValues(Map.of(), false), CatalogErrorCode.UNKNOWN_ATTRIBUTE_VALUE);
        assertCode(
                () -> eggs.replaceExclusions(List.of(Exclusion.of(List.of(UUID.randomUUID())))),
                CatalogErrorCode.UNKNOWN_ATTRIBUTE_VALUE);
    }

    @Test
    void cat05AttributeTypesAreFixedOnceVariantsExistButValuesMayBeAdded() {
        Product eggs = eggs();
        AttributeDraft grade =
                new AttributeDraft("GRADE", "Grade", 3, List.of(new AttributeValueDraft("A", "A", null)));
        UUID size = eggs.axes().get(1).id();

        assertCode(() -> eggs.addAttribute(grade, true, ids()), CatalogErrorCode.ATTRIBUTE_TYPES_LOCKED);
        AttributeType changed = eggs.changeAttribute(
                size, "Egg size", 5, List.of(new AttributeValueChange(null, "XXL", "XXL", null)), ids());

        assertThat(changed.name()).isEqualTo("Egg size");
        assertThat(changed.values()).extracting(AttributeValue::code).containsExactly("XS", "L", "XXL");
        assertThat(eggs.addAttribute(grade, false, ids()).code()).isEqualTo("GRADE");
        assertCode(() -> eggs.addAttribute(grade, false, ids()), CatalogErrorCode.ATTRIBUTE_CODE_TAKEN);
    }

    @Test
    void shouldRenameAndResortValuesButNeverReuseACode() {
        Product eggs = eggs();
        AttributeType colour = eggs.axes().getFirst();
        UUID red = colour.findValueByCode("red").orElseThrow().id();

        eggs.changeAttribute(colour.id(), null, null, List.of(new AttributeValueChange(red, null, "Brown", 9)), ids());

        assertThat(colour.findValue(red).orElseThrow().value()).isEqualTo("Brown");
        assertThat(colour.values().getLast().code()).isEqualTo("RED");
        assertCode(
                () -> eggs.changeAttribute(
                        colour.id(), null, null, List.of(new AttributeValueChange(null, "white", "Snow", null)), ids()),
                CatalogErrorCode.ATTRIBUTE_VALUE_CODE_TAKEN);
        assertCode(
                () -> eggs.changeAttribute(
                        colour.id(),
                        null,
                        null,
                        List.of(new AttributeValueChange(UUID.randomUUID(), null, "X", null)),
                        ids()),
                CatalogErrorCode.UNKNOWN_ATTRIBUTE_VALUE);
        assertThatThrownBy(() -> eggs.changeAttribute(
                        colour.id(), null, null, List.of(new AttributeValueChange(null, "X", null, null)), ids()))
                .isInstanceOf(ValidationFailure.class);
        assertCode(
                () -> eggs.changeAttribute(UUID.randomUUID(), "X", null, List.of(), ids()),
                CatalogErrorCode.ATTRIBUTE_NOT_FOUND);
    }

    @Test
    void shouldRefuseMoreThanFiveAttributes() {
        List<AttributeDraft> six = java.util.stream.IntStream.range(0, 6)
                .mapToObj(
                        i -> new AttributeDraft("A" + i, "A" + i, i, List.of(new AttributeValueDraft("V", "V", null))))
                .toList();

        assertThatThrownBy(() -> Product.create(
                        ProductId.of(UUID.randomUUID()),
                        TENANT,
                        new NewProduct(
                                "X",
                                "X",
                                ProductTemplate.VARIANT_MATRIX,
                                PIECE.id(),
                                null,
                                null,
                                Tracking.NONE,
                                "{}",
                                six),
                        ids()))
                .isInstanceOf(ValidationFailure.class);
    }

    @Test
    void cat03ConversionsArePositiveBetweenTwoUnitsAndOncePerPair() {
        Product oil = eggs();
        ProductConversion density =
                new ProductConversion(KG.id(), ML.id(), new BigDecimal("1086.96"), ConversionSource.DENSITY);

        oil.replaceConversions(List.of(density));
        assertThat(oil.conversions()).containsExactly(density);

        assertCode(
                () -> oil.replaceConversions(
                        List.of(new ProductConversion(KG.id(), ML.id(), BigDecimal.ZERO, ConversionSource.DENSITY))),
                CatalogErrorCode.INVALID_CONVERSION);
        assertCode(
                () -> oil.replaceConversions(
                        List.of(new ProductConversion(KG.id(), KG.id(), BigDecimal.ONE, ConversionSource.STANDARD))),
                CatalogErrorCode.INVALID_CONVERSION);
        assertCode(
                () -> oil.replaceConversions(List.of(
                        density,
                        new ProductConversion(ML.id(), KG.id(), new BigDecimal("0.00092"), ConversionSource.DENSITY))),
                CatalogErrorCode.INVALID_CONVERSION);
        assertThat(oil.conversions())
                .as("a refused replacement changes nothing")
                .containsExactly(density);
    }

    @Test
    void cat11ProductsAreDeactivatedNeverDeleted() {
        Product eggs = eggs();

        eggs.deactivate();
        assertThat(eggs.status()).isEqualTo(CatalogStatus.INACTIVE);
        eggs.reactivate();
        assertThat(eggs.isActive()).isTrue();
    }

    @Test
    void shouldChangeTheProductsDetails() {
        Product eggs = eggs();
        UUID category = UUID.randomUUID();
        UUID tax = UUID.randomUUID();

        eggs.rename("Farm eggs");
        eggs.changeCategory(category);
        eggs.changeTaxCategory(tax);
        eggs.changeTracking(Tracking.weighedLoose());
        eggs.replaceProperties("{\"origin\": \"Kurunegala\"}");
        eggs.touch(3);

        assertThat(eggs.name()).isEqualTo("Farm eggs");
        assertThat(eggs.categoryId()).isEqualTo(category);
        assertThat(eggs.taxCategoryId()).isEqualTo(tax);
        assertThat(eggs.tracking().weighed()).isTrue();
        assertThat(eggs.propertiesJson()).contains("Kurunegala");
        assertThat(eggs.revision()).isEqualTo(3);
        assertThat(eggs.template()).isEqualTo(ProductTemplate.VARIANT_MATRIX);
        assertThat(eggs.baseUnitId()).isEqualTo(PIECE.id());
    }
}
