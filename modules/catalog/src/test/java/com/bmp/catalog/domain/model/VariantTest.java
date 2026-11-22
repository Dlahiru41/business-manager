package com.bmp.catalog.domain.model;

import static com.bmp.catalog.domain.CatalogFixtures.KG;
import static com.bmp.catalog.domain.CatalogFixtures.assertCode;
import static com.bmp.catalog.domain.CatalogFixtures.base;
import static com.bmp.catalog.domain.CatalogFixtures.egg;
import static com.bmp.catalog.domain.CatalogFixtures.oil;
import static com.bmp.catalog.domain.CatalogFixtures.unit;
import static com.bmp.catalog.domain.CatalogFixtures.variant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.catalog.domain.error.CatalogErrorCode;
import com.bmp.shared.error.ValidationFailure;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VariantTest {

    private static final List<String> DEFAULT = List.of(StockStates.DEFAULT);

    private static PackagingUnitChange change() {
        return new PackagingUnitChange(null, null, null, null, null, null, null, null);
    }

    @Test
    void cat02AVariantHasExactlyOneBaseUnitWithQuantityOne() {
        Variant egg = egg();

        assertThat(egg.baseUnit().code()).isEqualTo("piece");
        assertThat(egg.baseUnit().qtyInBase()).isEqualByComparingTo("1");
        assertCode(
                () -> variant("X", DEFAULT, SellMode.PREPACKED, unit("tray", "30", StockStates.DEFAULT, true, true)),
                CatalogErrorCode.INVALID_QTY_IN_BASE);
        assertCode(
                () -> variant(
                        "X",
                        DEFAULT,
                        SellMode.PREPACKED,
                        base("piece", StockStates.DEFAULT, true, true),
                        base("pair", StockStates.DEFAULT, true, true)),
                CatalogErrorCode.INVALID_QTY_IN_BASE);
        PackagingUnitDraft doubled =
                new PackagingUnitDraft("piece", "Piece", new BigDecimal("2"), null, "DEFAULT", true, true, true, false);
        assertCode(() -> variant("X", DEFAULT, SellMode.PREPACKED, doubled), CatalogErrorCode.INVALID_QTY_IN_BASE);
    }

    @Test
    void cat02TheBaseUnitCannotBeResizedOrDeactivated() {
        Variant egg = egg();
        UUID piece = egg.baseUnit().id();

        assertCode(
                () -> egg.changePackagingUnit(
                        piece, new PackagingUnitChange(null, new BigDecimal("2"), null, null, null, null, null, null)),
                CatalogErrorCode.BASE_UNIT_IMMUTABLE);
        assertCode(
                () -> egg.changePackagingUnit(
                        piece,
                        new PackagingUnitChange(null, null, null, null, null, null, null, CatalogStatus.INACTIVE)),
                CatalogErrorCode.BASE_UNIT_IMMUTABLE);
        assertCode(
                () -> egg.addPackagingUnit(base("pair", StockStates.DEFAULT, true, true), UUID.randomUUID()),
                CatalogErrorCode.BASE_UNIT_IMMUTABLE);
    }

    @Test
    void cat04QuantitiesInBasePositiveAndMayBeFractional() {
        Variant oil = oil();

        PackagingUnit small =
                oil.addPackagingUnit(unit("bottle-187", "187.5", StockStates.OPEN, false, true), UUID.randomUUID());

        assertThat(small.qtyInBase()).isEqualByComparingTo("187.5");
        assertThat(small.qtyInBase().scale()).isEqualTo(Variant.QTY_SCALE);
        assertThat(small.toBase(new BigDecimal("4"))).isEqualByComparingTo("750");
        assertCode(
                () -> oil.addPackagingUnit(unit("zero", "0", StockStates.OPEN, false, true), UUID.randomUUID()),
                CatalogErrorCode.INVALID_QTY_IN_BASE);
        assertCode(
                () -> oil.addPackagingUnit(unit("minus", "-1", StockStates.OPEN, false, true), UUID.randomUUID()),
                CatalogErrorCode.INVALID_QTY_IN_BASE);
        assertCode(
                () -> oil.addPackagingUnit(unit("dust", "0.0000001", StockStates.OPEN, false, true), UUID.randomUUID()),
                CatalogErrorCode.INVALID_QTY_IN_BASE);
        assertCode(() -> Variant.normalizedQty(new BigDecimal("1E+15"), "huge"), CatalogErrorCode.INVALID_QTY_IN_BASE);
    }

    @Test
    void cat04PackagingUnitCodesAreUniquePerVariant() {
        Variant egg = egg();

        assertCode(
                () -> egg.addPackagingUnit(unit("TRAY", "24", StockStates.DEFAULT, true, true), UUID.randomUUID()),
                CatalogErrorCode.PACKAGING_UNIT_CODE_TAKEN);
    }

    @Test
    void cat07AUnitDrawsFromOneOfTheVariantsStockStates() {
        assertCode(
                () -> variant(
                        "X",
                        DEFAULT,
                        SellMode.PREPACKED,
                        base("piece", StockStates.DEFAULT, true, true),
                        unit("bottle", "750", StockStates.OPEN, false, true)),
                CatalogErrorCode.INVALID_STOCK_STATE);
        Variant oil = oil();
        UUID bottle = oil.packagingUnits().stream()
                .filter(u -> u.code().equals("bottle-750"))
                .findFirst()
                .orElseThrow()
                .id();
        assertCode(
                () -> oil.changePackagingUnit(
                        bottle, new PackagingUnitChange(null, null, null, "DEFAULT", null, null, null, null)),
                CatalogErrorCode.INVALID_STOCK_STATE);
        assertCode(() -> oil.changeStockStates(List.of(StockStates.SEALED)), CatalogErrorCode.INVALID_STOCK_STATE);
        assertCode(() -> oil.changeStockStates(List.of("lower-case")), CatalogErrorCode.INVALID_STOCK_STATE);

        oil.changeStockStates(List.of(StockStates.SEALED, StockStates.OPEN, "RESERVED"));
        assertThat(oil.stockStates()).containsExactly("SEALED", "OPEN", "RESERVED");
    }

    @Test
    void cat08SellableAndPurchasableNeedAnActiveUnitThatAllowsIt() {
        Variant oil = oil();
        assertThat(oil.isSellable()).isTrue();
        assertThat(oil.isPurchasable()).isTrue();

        Variant soldOnly = variant("S", DEFAULT, SellMode.PREPACKED, base("piece", StockStates.DEFAULT, false, true));
        assertThat(soldOnly.isSellable()).isTrue();
        assertThat(soldOnly.isPurchasable()).isFalse();

        UUID can = unitId(oil, "can");
        oil.changePackagingUnit(
                can, new PackagingUnitChange(null, null, null, null, null, null, null, CatalogStatus.INACTIVE));
        assertThat(oil.isPurchasable()).as("the only buyable unit is inactive").isFalse();

        assertThat(soldOnly.deactivate()).isTrue();
        assertThat(soldOnly.deactivate()).isFalse();
        assertThat(soldOnly.isSellable()).isFalse();
        assertThat(soldOnly.reactivate()).isTrue();
        assertThat(soldOnly.reactivate()).isFalse();
        assertThat(soldOnly.isSellable()).isTrue();
    }

    @Test
    void cat12ABarcodeMapsToOneUnitOfTheVariant() {
        Variant egg = egg();
        UUID piece = egg.baseUnit().id();
        UUID tray = unitId(egg, "tray");

        egg.addBarcode(tray, "4792173000011");

        assertThat(egg.packagingUnit(tray).orElseThrow().barcodes()).containsExactly("4792173000011");
        assertCode(() -> egg.addBarcode(piece, "4792173000011"), CatalogErrorCode.BARCODE_ALREADY_EXISTS);
        assertCode(() -> egg.addBarcode(tray, "4792173000011"), CatalogErrorCode.BARCODE_ALREADY_EXISTS);
        assertCode(() -> egg.removeBarcode(piece, "4792173000011"), CatalogErrorCode.BARCODE_NOT_FOUND);
        egg.removeBarcode(tray, "4792173000011");
        assertThat(egg.packagingUnit(tray).orElseThrow().barcodes()).isEmpty();
        assertCode(() -> egg.addBarcode(UUID.randomUUID(), "1"), CatalogErrorCode.PACKAGING_UNIT_NOT_FOUND);
    }

    @Test
    void cat14UnitsDefinedInAnotherUnitAreResizedWhenTheConversionChanges() {
        Variant oil = oil();
        UUID can = unitId(oil, "can");

        Map<UUID, BigDecimal> changed =
                oil.resizeDefinedUnits(defined -> defined.unitId().equals(KG.id())
                        ? defined.value().multiply(new BigDecimal("1087.5"))
                        : defined.value());

        assertThat(changed).containsOnlyKeys(can);
        assertThat(changed.get(can)).isEqualByComparingTo("19575");
        assertThat(oil.packagingUnit(can).orElseThrow().qtyInBase()).isEqualByComparingTo("19575");
        assertThat(oil.resizeDefinedUnits(defined -> defined.value().multiply(new BigDecimal("1087.5"))))
                .as("unchanged sizes report nothing")
                .isEmpty();
    }

    @Test
    void cat06SkusMayBeOverriddenAndAreKeptUpperCase() {
        Variant egg = egg();

        egg.changeSku("egg-r-l");

        assertThat(egg.sku()).isEqualTo("EGG-R-L");
        assertThatThrownBy(() -> egg.changeSku("bad sku")).isInstanceOf(ValidationFailure.class);
    }

    @Test
    void shouldKeepOneDefaultSellUnit() {
        Variant egg = egg();
        UUID tray = unitId(egg, "tray");
        assertThat(egg.baseUnit().isDefaultSell())
                .as("the base unit is the default when none is named")
                .isTrue();

        egg.changePackagingUnit(tray, new PackagingUnitChange(null, null, null, null, null, null, true, null));

        assertThat(egg.packagingUnits())
                .filteredOn(PackagingUnit::isDefaultSell)
                .extracting(PackagingUnit::code)
                .containsExactly("tray");
        egg.changePackagingUnit(tray, new PackagingUnitChange(null, null, null, null, null, null, false, null));
        assertThat(egg.packagingUnits()).noneMatch(PackagingUnit::isDefaultSell);
        PackagingUnit box = egg.addPackagingUnit(
                new PackagingUnitDraft("box", "Box", new BigDecimal("12"), null, "DEFAULT", false, true, true, true),
                UUID.randomUUID());
        assertThat(box.isDefaultSell()).isTrue();
    }

    @Test
    void shouldChangeUnitDetails() {
        Variant egg = egg();
        UUID tray = unitId(egg, "tray");

        PackagingUnit changed = egg.changePackagingUnit(
                tray,
                new PackagingUnitChange("Tray (30)", new BigDecimal("30.0"), null, null, false, null, null, null));

        assertThat(changed.name()).isEqualTo("Tray (30)");
        assertThat(changed.canBuy()).isFalse();
        assertThat(changed.canSell()).isTrue();
        assertThat(egg.packagingUnits()).extracting(PackagingUnit::code).containsExactly("piece", "tray");
        assertThat(change().resizes()).isFalse();
        egg.rename("Eggs Red Large");
        egg.changeSellMode(SellMode.BREAK_ON_SALE);
        egg.touch(7);
        assertThat(egg.name()).isEqualTo("Eggs Red Large");
        assertThat(egg.sellMode()).isEqualTo(SellMode.BREAK_ON_SALE);
        assertThat(egg.revision()).isEqualTo(7);
        assertThat(egg.version()).isZero();
    }

    private static UUID unitId(Variant variant, String code) {
        return variant.packagingUnits().stream()
                .filter(u -> u.code().equals(code))
                .findFirst()
                .orElseThrow()
                .id();
    }
}
