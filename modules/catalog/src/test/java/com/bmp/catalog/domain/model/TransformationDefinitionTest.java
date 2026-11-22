package com.bmp.catalog.domain.model;

import static com.bmp.catalog.domain.CatalogFixtures.TENANT;
import static com.bmp.catalog.domain.CatalogFixtures.assertCode;
import static com.bmp.catalog.domain.CatalogFixtures.ids;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.catalog.domain.error.CatalogErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransformationDefinitionTest {

    private static final UUID OIL = UUID.randomUUID();
    private static final UUID CAN = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-21T09:00:00Z");

    private static LineDraft in(String qty) {
        return new LineDraft(Direction.IN, OIL, "SEALED", new BigDecimal(qty), CAN, BigDecimal.ONE, null);
    }

    private static LineDraft out(String qty, String share) {
        return new LineDraft(
                Direction.OUT,
                OIL,
                "OPEN",
                new BigDecimal(qty),
                CAN,
                BigDecimal.ONE,
                share == null ? null : new BigDecimal(share));
    }

    private static TransformationDefinition openCan(List<LineDraft> lines) {
        return TransformationDefinition.draft(
                UUID.randomUUID(), TENANT, "oil-open-can", "Open can", TransformationKind.OPEN, null, lines, ids());
    }

    @Test
    void cat10NeedsAtLeastOneInAndOneOutLine() {
        assertCode(() -> openCan(List.of(in("19565.28"))), CatalogErrorCode.DEFINITION_LINES_INVALID);
        assertCode(() -> openCan(List.of(out("19565.28", "100"))), CatalogErrorCode.DEFINITION_LINES_INVALID);
        assertCode(() -> openCan(List.of(in("0"), out("1", null))), CatalogErrorCode.INVALID_QTY_IN_BASE);
    }

    @Test
    void cat10OutCostSharesSumToHundredAndASingleOutLineDefaultsToHundred() {
        TransformationDefinition single = openCan(List.of(in("19565.28"), out("19565.28", null)));

        assertThat(single.lines().getLast().costSharePct()).isEqualByComparingTo("100");
        assertThat(single.code()).isEqualTo("OIL-OPEN-CAN");
        assertCode(
                () -> openCan(List.of(in("10"), out("6", "60"), out("4", "30"))), CatalogErrorCode.COST_SHARE_INVALID);
        assertCode(
                () -> openCan(List.of(in("10"), out("6", "60"), out("4", null))), CatalogErrorCode.COST_SHARE_INVALID);
        assertCode(
                () -> openCan(List.of(in("10"), out("6", "110"), out("4", "-10"))),
                CatalogErrorCode.COST_SHARE_INVALID);
        assertThat(openCan(List.of(in("10"), out("6", "60"), out("4", "40"))).lines())
                .hasSize(3);
    }

    @Test
    void cat10PublishingTakesTheNextVersionAndEditsStartANewDraft() {
        TransformationDefinition definition = openCan(List.of(in("19565.28"), out("19565.28", null)));
        assertThat(definition.status()).isEqualTo(DefinitionStatus.DRAFT);
        assertThat(definition.defVersion()).isZero();

        definition.publish(NOW);
        assertThat(definition.status()).isEqualTo(DefinitionStatus.PUBLISHED);
        assertThat(definition.defVersion()).isEqualTo(1);
        assertThat(definition.publishedAt()).isEqualTo(NOW);
        assertCode(() -> definition.publish(NOW), CatalogErrorCode.DEFINITION_ALREADY_PUBLISHED);

        definition.edit("Open can 18 kg", new BigDecimal("98.5"), null, ids());
        assertThat(definition.status()).isEqualTo(DefinitionStatus.DRAFT);
        assertThat(definition.defVersion())
                .as("the published version stays until the draft is published")
                .isEqualTo(1);
        definition.publish(NOW.plusSeconds(60));
        assertThat(definition.defVersion()).isEqualTo(2);
        assertThat(definition.expectedYieldPct()).isEqualByComparingTo("98.5");
        assertThat(definition.name()).isEqualTo("Open can 18 kg");
    }

    @Test
    void cat11RetiredDefinitionsCanNeitherChangeNorBePublished() {
        TransformationDefinition definition = openCan(List.of(in("1"), out("1", null)));

        assertThat(definition.retire()).isTrue();
        assertThat(definition.retire()).isFalse();
        assertCode(() -> definition.publish(NOW), CatalogErrorCode.DEFINITION_RETIRED);
        assertCode(() -> definition.edit("x", null, null, ids()), CatalogErrorCode.DEFINITION_RETIRED);
        assertThat(definition.resizeLines(Map.of(CAN, BigDecimal.TEN))).isFalse();
    }

    @Test
    void cat14LinesSizedByAPackagingUnitFollowItsNewSize() {
        TransformationDefinition definition = openCan(List.of(in("19565.28"), out("19565.28", null)));
        definition.publish(NOW);

        boolean changed = definition.resizeLines(Map.of(CAN, new BigDecimal("19575")));

        assertThat(changed).isTrue();
        assertThat(definition.lines())
                .allSatisfy(line -> assertThat(line.qtyBase()).isEqualByComparingTo("19575"));
        assertThat(definition.status()).isEqualTo(DefinitionStatus.DRAFT);
        assertThat(definition.resizeLines(Map.of(CAN, new BigDecimal("19575")))).isFalse();
        assertThat(definition.resizeLines(Map.of(UUID.randomUUID(), BigDecimal.ONE)))
                .isFalse();
        definition.publish(NOW);
        assertThat(definition.defVersion()).isEqualTo(2);
        assertThat(definition.uses(OIL)).isTrue();
        assertThat(definition.uses(UUID.randomUUID())).isFalse();
    }

    @Test
    void shouldReplaceLinesOnEdit() {
        TransformationDefinition definition = openCan(List.of(in("10"), out("10", null)));

        definition.edit(null, null, List.of(in("20"), out("15", "75"), out("5", "25")), ids());

        assertThat(definition.lines()).extracting(TransformationLine::lineNo).containsExactly(1, 2, 3);
        assertThat(definition.lines().getFirst().qtyBase()).isEqualByComparingTo("20");
        assertThat(definition.kind()).isEqualTo(TransformationKind.OPEN);
    }
}
