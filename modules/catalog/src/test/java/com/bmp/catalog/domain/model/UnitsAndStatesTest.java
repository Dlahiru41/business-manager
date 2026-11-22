package com.bmp.catalog.domain.model;

import static com.bmp.catalog.domain.CatalogFixtures.KG;
import static com.bmp.catalog.domain.CatalogFixtures.ML;
import static com.bmp.catalog.domain.CatalogFixtures.PACK;
import static com.bmp.catalog.domain.CatalogFixtures.PIECE;
import static com.bmp.catalog.domain.CatalogFixtures.TENANT;
import static com.bmp.catalog.domain.CatalogFixtures.assertCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.catalog.domain.error.CatalogErrorCode;
import com.bmp.shared.error.ValidationFailure;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class UnitsAndStatesTest {

    @Test
    void cat01TenantsMayAddTheirOwnUnits() {
        UnitOfMeasure bushel = UnitOfMeasure.custom(
                UUID.randomUUID(), TENANT.value(), "bushel", "Bushel", Dimension.VOLUME, new BigDecimal("35.2391"));
        UnitOfMeasure bundle =
                UnitOfMeasure.custom(UUID.randomUUID(), TENANT.value(), "bundle", "Bundle", Dimension.COUNT, null);

        assertThat(bushel.isStandard()).isFalse();
        assertThat(KG.isStandard()).isTrue();
        assertThat(bushel.convertsBySiFactorTo(ML)).isTrue();
        assertThat(bundle.convertsBySiFactorTo(PIECE))
                .as("no factor, no SI conversion")
                .isFalse();
        assertThat(PACK.convertsBySiFactorTo(PIECE)).isFalse();
        assertThat(KG.convertsBySiFactorTo(ML)).isFalse();
        assertThatThrownBy(() -> UnitOfMeasure.custom(
                        UUID.randomUUID(), TENANT.value(), "1kg", "Bad", Dimension.MASS, BigDecimal.ONE))
                .isInstanceOf(ValidationFailure.class);
        assertThatThrownBy(() -> UnitOfMeasure.custom(
                        UUID.randomUUID(), TENANT.value(), "zero", "Zero", Dimension.MASS, BigDecimal.ZERO))
                .isInstanceOf(ValidationFailure.class);
    }

    @Test
    void cat07StockStatesAreUpperCaseCodesWithoutDuplicates() {
        assertThat(StockStates.validated(List.of("SEALED", "OPEN", "SEALED"))).containsExactly("SEALED", "OPEN");
        assertThat(StockStates.packedStateFor("packet-250")).isEqualTo("PACKED_PACKET_250");
        assertThat(StockStates.packedStateFor("p." + "x".repeat(40)))
                .hasSize(32)
                .startsWith("PACKED_P_X");
        assertCode(() -> StockStates.validated(List.of()), CatalogErrorCode.INVALID_STOCK_STATE);
        assertCode(() -> StockStates.validated(List.of("open")), CatalogErrorCode.INVALID_STOCK_STATE);
        assertCode(
                () -> StockStates.validated(
                        IntStream.range(0, 21).mapToObj(i -> "S" + i).toList()),
                CatalogErrorCode.INVALID_STOCK_STATE);
        assertCode(
                () -> StockStates.requireIn(Collections.singletonList("DEFAULT"), "OPEN", "bottle"),
                CatalogErrorCode.INVALID_STOCK_STATE);
    }

    @Test
    void cat05AnExclusionIsASetOfValues() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        assertThat(Exclusion.of(List.of(a, b))).isEqualTo(Exclusion.of(List.of(b, a, b)));
        assertThat(Exclusion.of(List.of(a)).excludes(List.of(a, b))).isTrue();
        assertThat(Exclusion.of(List.of(a, b)).excludes(List.of(a))).isFalse();
        assertThatThrownBy(() -> Exclusion.of(List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void trackingPresetsMatchTheGoods() {
        assertThat(Tracking.counted().decimalQty()).isFalse();
        assertThat(Tracking.measured().decimalQty()).isTrue();
        assertThat(Tracking.weighedLoose().weighed()).isTrue();
        ProductConversion conversion =
                new ProductConversion(KG.id(), ML.id(), BigDecimal.TEN, ConversionSource.DENSITY);
        assertThat(conversion.links(ML.id(), KG.id())).isTrue();
        assertThat(conversion.links(KG.id(), KG.id())).isFalse();
    }
}
