package com.bmp.catalog.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SkuGeneratorTest {

    @Test
    void cat06JoinsTheProductCodeAndValueCodesInUpperCase() {
        assertThat(SkuGenerator.generate("egg", List.of("red", "xs"), sku -> false))
                .isEqualTo("EGG-RED-XS");
        assertThat(SkuGenerator.generate("OIL", List.of(), sku -> false)).isEqualTo("OIL");
    }

    @Test
    void cat06DeduplicatesWithANumericSuffix() {
        Set<String> taken = Set.of("EGG-RED-XS", "EGG-RED-XS-2");

        assertThat(SkuGenerator.generate("EGG", List.of("RED", "XS"), taken::contains))
                .isEqualTo("EGG-RED-XS-3");
    }

    @Test
    void cat06KeepsSkusWithinSixtyFourCharactersIncludingTheSuffix() {
        String longCode = "A".repeat(40);
        String base = SkuGenerator.base(longCode, List.of("B".repeat(30)));

        String sku = SkuGenerator.generate(longCode, List.of("B".repeat(30)), base::equals);

        assertThat(base).hasSize(64);
        assertThat(sku).hasSize(64).endsWith("-2");
    }

    @Test
    void cat06ReplacesCharactersASkuCannotHold() {
        assertThat(SkuGenerator.base("soya", List.of("my soy", "90g"))).isEqualTo("SOYA-MY_SOY-90G");
    }
}
