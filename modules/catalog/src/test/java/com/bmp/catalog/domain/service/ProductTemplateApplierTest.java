package com.bmp.catalog.domain.service;

import static com.bmp.catalog.domain.CatalogFixtures.assertCode;
import static com.bmp.catalog.domain.CatalogFixtures.base;
import static com.bmp.catalog.domain.CatalogFixtures.oil;
import static com.bmp.catalog.domain.CatalogFixtures.unit;
import static com.bmp.catalog.domain.CatalogFixtures.variant;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.catalog.domain.error.CatalogErrorCode;
import com.bmp.catalog.domain.model.Direction;
import com.bmp.catalog.domain.model.LineDraft;
import com.bmp.catalog.domain.model.PackagingUnit;
import com.bmp.catalog.domain.model.ProductTemplate;
import com.bmp.catalog.domain.model.SellMode;
import com.bmp.catalog.domain.model.StockStates;
import com.bmp.catalog.domain.model.TransformationKind;
import com.bmp.catalog.domain.model.Variant;
import com.bmp.catalog.domain.service.TemplateStrategy.DefinitionPlan;
import com.bmp.catalog.domain.service.TemplateStrategy.UnitSpec;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductTemplateApplierTest {

    @Test
    void cat09EveryTemplateHasDefaults() {
        assertThat(ProductTemplateApplier.all())
                .extracting(TemplateStrategy::template)
                .containsExactly(ProductTemplate.values());
        assertThat(ProductTemplateApplier.strategy(ProductTemplate.BRANDED_PACKET)
                        .defaults()
                        .baseUnitCode())
                .isEqualTo("pack");
        assertThat(ProductTemplateApplier.strategy(ProductTemplate.SERVICE)
                        .defaults()
                        .tracksStock())
                .isFalse();
        assertThat(ProductTemplateApplier.strategy(ProductTemplate.LOOSE_REPACK)
                        .defaults()
                        .tracking()
                        .weighed())
                .isTrue();
    }

    @Test
    void cat09MatrixTemplatesNeedAttributes() {
        assertCode(
                () -> ProductTemplateApplier.requireAttributes(ProductTemplate.VARIANT_MATRIX, false),
                CatalogErrorCode.ATTRIBUTES_REQUIRED);
        assertCode(
                () -> ProductTemplateApplier.requireAttributes(ProductTemplate.BRANDED_PACKET, false),
                CatalogErrorCode.ATTRIBUTES_REQUIRED);
        ProductTemplateApplier.requireAttributes(ProductTemplate.SIMPLE, false);
        ProductTemplateApplier.requireAttributes(ProductTemplate.VARIANT_MATRIX, true);
    }

    @Test
    void cat09BulkBreakKeepsBoughtUnitsSealedAndSellsTheRestOpen() {
        TemplateStrategy bulk = ProductTemplateApplier.strategy(ProductTemplate.BULK_BREAK);

        assertThat(bulk.stockStates(List.of())).containsExactly("SEALED", "OPEN");
        assertThat(bulk.defaults().sellMode()).isEqualTo(SellMode.BREAK_ON_SALE);
        assertThat(bulk.defaultStockState(new UnitSpec("ml", true, false, false)))
                .isEqualTo("OPEN");
        assertThat(bulk.defaultStockState(new UnitSpec("can", false, true, true)))
                .isEqualTo("SEALED");
        assertThat(bulk.defaultStockState(new UnitSpec("bottle-750", false, false, true)))
                .isEqualTo("OPEN");
    }

    @Test
    void cat09BulkBreakCreatesAnOpenDefinitionPerSealedUnit() {
        Variant oil = oil();
        PackagingUnit can = oil.packagingUnits().stream()
                .filter(u -> u.code().equals("can"))
                .findFirst()
                .orElseThrow();

        List<DefinitionPlan> plans =
                ProductTemplateApplier.strategy(ProductTemplate.BULK_BREAK).transformations("OIL", oil);

        assertThat(plans).hasSize(1);
        DefinitionPlan open = plans.getFirst();
        assertThat(open.code()).isEqualTo("OIL-OPEN-CAN");
        assertThat(open.kind()).isEqualTo(TransformationKind.OPEN);
        assertThat(open.lines()).extracting(LineDraft::direction).containsExactly(Direction.IN, Direction.OUT);
        assertThat(open.lines()).extracting(LineDraft::stockState).containsExactly("SEALED", "OPEN");
        assertThat(open.lines()).allSatisfy(line -> {
            assertThat(line.qtyBase()).isEqualByComparingTo("19565.28");
            assertThat(line.packagingUnitId()).isEqualTo(can.id());
        });
        assertThat(open.lines().getLast().costSharePct()).isEqualByComparingTo("100");
    }

    @Test
    void cat09LooseRepackGivesOwnPacketsTheirOwnStateAndARepackDefinition() {
        TemplateStrategy loose = ProductTemplateApplier.strategy(ProductTemplate.LOOSE_REPACK);
        List<UnitSpec> units = List.of(
                new UnitSpec("g", true, true, true),
                new UnitSpec("kg", false, true, true),
                new UnitSpec("packet-250", false, false, true),
                new UnitSpec("packet-500", false, false, true));

        List<String> states = loose.stockStates(units);

        assertThat(states).containsExactly("DEFAULT", "PACKED_PACKET_250", "PACKED_PACKET_500");
        assertThat(loose.defaultStockState(units.get(1))).isEqualTo("DEFAULT");
        Variant karola = variant(
                "KAROLA",
                states,
                SellMode.PREPACKED,
                base("g", StockStates.DEFAULT, true, true),
                unit("kg", "1000", StockStates.DEFAULT, true, true),
                unit("packet-250", "250", "PACKED_PACKET_250", false, true),
                unit("packet-500", "500", "PACKED_PACKET_500", false, true));

        List<DefinitionPlan> plans = loose.transformations("KAROLA", karola);

        assertThat(plans)
                .extracting(DefinitionPlan::code)
                .containsExactly("KAROLA-REPACK-PACKET-250", "KAROLA-REPACK-PACKET-500");
        DefinitionPlan small = plans.getFirst();
        assertThat(small.kind()).isEqualTo(TransformationKind.REPACK);
        assertThat(small.lines()).extracting(LineDraft::stockState).containsExactly("DEFAULT", "PACKED_PACKET_250");
        assertThat(small.lines()).allSatisfy(line -> assertThat(line.qtyBase()).isEqualByComparingTo("250"));
    }

    @Test
    void cat09OtherTemplatesKeepEverythingInDefaultAndImplyNoTransformation() {
        TemplateStrategy matrix = ProductTemplateApplier.strategy(ProductTemplate.VARIANT_MATRIX);

        assertThat(matrix.stockStates(List.of())).containsExactly("DEFAULT");
        assertThat(matrix.defaultStockState(new UnitSpec("tray", false, true, true)))
                .isEqualTo("DEFAULT");
        assertThat(matrix.transformations("OIL", oil())).isEmpty();
    }
}
