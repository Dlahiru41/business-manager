package com.bmp.catalog.domain.service;

import static com.bmp.catalog.domain.CatalogFixtures.DOZEN;
import static com.bmp.catalog.domain.CatalogFixtures.G;
import static com.bmp.catalog.domain.CatalogFixtures.KG;
import static com.bmp.catalog.domain.CatalogFixtures.L;
import static com.bmp.catalog.domain.CatalogFixtures.ML;
import static com.bmp.catalog.domain.CatalogFixtures.PACK;
import static com.bmp.catalog.domain.CatalogFixtures.PIECE;
import static com.bmp.catalog.domain.CatalogFixtures.UNITS;
import static com.bmp.catalog.domain.CatalogFixtures.assertCode;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.catalog.domain.error.CatalogErrorCode;
import com.bmp.catalog.domain.model.ConversionSource;
import com.bmp.catalog.domain.model.ProductConversion;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UnitConverterTest {

    private static final ProductConversion OIL_DENSITY =
            new ProductConversion(KG.id(), ML.id(), new BigDecimal("1086.96"), ConversionSource.DENSITY);

    private final UnitConverter plain = new UnitConverter(UNITS, List.of());
    private final UnitConverter oil = new UnitConverter(UNITS, List.of(OIL_DENSITY));

    @Test
    void cat03ConvertsWithinOneDimensionBySiFactorsBothWays() {
        assertThat(plain.convert(new BigDecimal("1500"), G.id(), KG.id())).isEqualByComparingTo("1.5");
        assertThat(plain.convert(new BigDecimal("0.25"), KG.id(), G.id())).isEqualByComparingTo("250");
        assertThat(plain.convert(new BigDecimal("750"), ML.id(), L.id())).isEqualByComparingTo("0.75");
        assertThat(plain.convert(new BigDecimal("2"), DOZEN.id(), PIECE.id())).isEqualByComparingTo("24");
        assertThat(plain.factor(PIECE.id(), PIECE.id())).isEqualByComparingTo("1");
    }

    @Test
    void cat03ConvertsAcrossDimensionsOnlyThroughTheProductConversion() {
        assertThat(oil.convert(new BigDecimal("18"), KG.id(), ML.id())).isEqualByComparingTo("19565.28");
        assertThat(oil.convert(new BigDecimal("1086.96"), ML.id(), KG.id())).isEqualByComparingTo("1");
        // SI steps on either side of the conversion: g → kg → ml and kg → ml → l.
        assertThat(oil.convert(new BigDecimal("500"), G.id(), ML.id())).isEqualByComparingTo("543.48");
        assertThat(oil.convert(new BigDecimal("18"), KG.id(), L.id())).isEqualByComparingTo("19.56528");
        assertThat(oil.canConvert(G.id(), L.id())).isTrue();
    }

    @Test
    void cat03RefusesUnitsOfDifferentDimensionsWithoutAConversion() {
        assertCode(() -> plain.factor(KG.id(), ML.id()), CatalogErrorCode.UNIT_NOT_CONVERTIBLE);
        assertThat(plain.canConvert(KG.id(), ML.id())).isFalse();
    }

    @Test
    void cat03RefusesUnitsWithoutAnSiFactorEvenInTheSameDimension() {
        assertCode(() -> plain.factor(PACK.id(), PIECE.id()), CatalogErrorCode.UNIT_NOT_CONVERTIBLE);
        assertThat(plain.factor(PACK.id(), PACK.id())).isEqualByComparingTo("1");
    }

    @Test
    void shouldRejectUnknownUnits() {
        assertCode(() -> plain.factor(UUID.randomUUID(), KG.id()), CatalogErrorCode.UNKNOWN_UNIT);
    }

    @Test
    void cat14AChangedFactorChangesLaterConversions() {
        UnitConverter measured = new UnitConverter(
                UNITS,
                List.of(new ProductConversion(
                        KG.id(), ML.id(), new BigDecimal("1087.5"), ConversionSource.MEASURED_YIELD)));

        assertThat(measured.convert(new BigDecimal("18"), KG.id(), ML.id())).isEqualByComparingTo("19575");
        assertThat(oil.convert(new BigDecimal("18"), KG.id(), ML.id())).isEqualByComparingTo("19565.28");
    }
}
