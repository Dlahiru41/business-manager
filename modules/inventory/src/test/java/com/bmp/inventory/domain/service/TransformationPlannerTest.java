package com.bmp.inventory.domain.service;

import static com.bmp.inventory.domain.InventoryFixtures.OIL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.inventory.domain.error.InventoryErrorCode;
import com.bmp.inventory.domain.model.DefinitionLine;
import com.bmp.inventory.domain.model.DefinitionRef;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.money.CurrencyCode;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransformationPlannerTest {

    private static final UUID PACKET = UUID.randomUUID();

    private static DefinitionRef openCan() {
        return new DefinitionRef(
                UUID.randomUUID(),
                1,
                "OIL-OPEN-CAN",
                "Open can",
                "OPEN",
                null,
                List.of(
                        new DefinitionLine("IN", OIL, "SEALED", new BigDecimal("19565.28"), null, null),
                        new DefinitionLine(
                                "OUT", OIL, "OPEN", new BigDecimal("19565.28"), null, new BigDecimal("100"))),
                true);
    }

    private static DefinitionRef threeWays() {
        return new DefinitionRef(
                UUID.randomUUID(),
                1,
                "SPLIT",
                "Split",
                "RECIPE",
                null,
                List.of(
                        new DefinitionLine("IN", OIL, "SEALED", BigDecimal.TEN, null, null),
                        new DefinitionLine("OUT", PACKET, "A", BigDecimal.ONE, null, new BigDecimal("33.3333")),
                        new DefinitionLine("OUT", PACKET, "B", BigDecimal.ONE, null, new BigDecimal("33.3333")),
                        new DefinitionLine("OUT", PACKET, "C", BigDecimal.ONE, null, new BigDecimal("33.3334"))),
                true);
    }

    @Test
    void inv09InputsAreTheInLinesTimesTheRunsAndOutputsDefaultToTheExpectedOnes() {
        DefinitionRef definition = openCan();

        List<TransformationPlanner.Output> outputs =
                TransformationPlanner.outputs(definition, new BigDecimal("2"), null);

        assertThat(TransformationPlanner.consumed(definition.inputs().getFirst(), new BigDecimal("2")))
                .isEqualByComparingTo("39130.56");
        assertThat(outputs).singleElement().satisfies(o -> {
            assertThat(o.expected()).isEqualByComparingTo("39130.56");
            assertThat(o.actual()).isEqualByComparingTo("39130.56");
            assertThat(o.sharePct()).isEqualByComparingTo("100");
        });
        assertThat(TransformationPlanner.yieldVariance(outputs)).isEqualByComparingTo("0");
    }

    @Test
    void inv10AnOpenedCanYieldsWhatWasMeasuredAndRecordsTheVariance() {
        List<TransformationPlanner.Output> outputs = TransformationPlanner.outputs(
                openCan(),
                BigDecimal.ONE,
                List.of(new TransformationPlanner.Actual(OIL, "OPEN", new BigDecimal("19400"))));

        assertThat(TransformationPlanner.yieldVariance(outputs)).isEqualByComparingTo("-165.28");
        assertThat(TransformationPlanner.costs(new BigDecimal("13800.00"), outputs, CurrencyCode.LKR))
                .singleElement()
                .satisfies(c -> assertThat(c).isEqualByComparingTo("13800.00"));
    }

    @Test
    void inv09InputCostIsSharedByLargestRemainderSoNothingIsLost() {
        List<TransformationPlanner.Output> outputs = TransformationPlanner.outputs(threeWays(), BigDecimal.ONE, null);

        List<BigDecimal> costs = TransformationPlanner.costs(new BigDecimal("100.00"), outputs, CurrencyCode.LKR);

        assertThat(costs.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("100.00");
        assertThat(costs).extracting(BigDecimal::toPlainString).containsExactly("33.33", "33.33", "33.34");
        DefinitionRef single = new DefinitionRef(
                UUID.randomUUID(),
                1,
                "X",
                "X",
                "REPACK",
                null,
                List.of(
                        new DefinitionLine("IN", OIL, "DEFAULT", BigDecimal.ONE, null, null),
                        new DefinitionLine("OUT", OIL, "PACKED", BigDecimal.ONE, null, null)),
                true);
        assertThat(TransformationPlanner.outputs(single, BigDecimal.ONE, null)
                        .getFirst()
                        .sharePct())
                .as("a single output without a share takes all")
                .isEqualByComparingTo("100");
    }

    @Test
    void actualOutputsMustNameEachOutLineOnceWithStock() {
        DefinitionRef definition = openCan();
        for (List<TransformationPlanner.Actual> wrong : List.of(
                List.<TransformationPlanner.Actual>of(),
                List.of(new TransformationPlanner.Actual(OIL, "SEALED", BigDecimal.ONE)),
                List.of(new TransformationPlanner.Actual(OIL, "OPEN", BigDecimal.ZERO)),
                List.of(
                        new TransformationPlanner.Actual(OIL, "OPEN", BigDecimal.ONE),
                        new TransformationPlanner.Actual(OIL, "OPEN", BigDecimal.ONE)))) {
            assertThatThrownBy(() -> TransformationPlanner.outputs(definition, BigDecimal.ONE, wrong))
                    .isInstanceOfSatisfying(
                            BusinessRuleViolation.class,
                            e -> assertThat(e.errorCode()).isEqualTo(InventoryErrorCode.INVALID_OUTPUTS));
        }
        assertThat(definition.inputs()).hasSize(1);
        assertThat(definition.outputs()).hasSize(1);
    }
}
