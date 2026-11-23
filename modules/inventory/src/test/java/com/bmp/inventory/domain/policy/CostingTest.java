package com.bmp.inventory.domain.policy;

import static com.bmp.inventory.domain.InventoryFixtures.NOW;
import static com.bmp.inventory.domain.InventoryFixtures.ROUNDING;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.inventory.domain.model.BalanceState;
import com.bmp.inventory.domain.model.CostLayer;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CostingTest {

    private static final CostingPolicy AVERAGE = CostingMethod.MOVING_AVERAGE.policy(ROUNDING);
    private static final CostingPolicy FIFO = CostingMethod.FIFO.policy(ROUNDING);

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    private static BalanceState receive(CostingPolicy policy, BalanceState state, String qty, String cost) {
        return policy.receive(state, d(qty), d(cost), UUID.randomUUID(), NOW).next();
    }

    @Test
    void inv03TheAverageIsWeightedByQuantity() {
        BalanceState first = receive(AVERAGE, BalanceState.empty(), "300", "12750.00");
        BalanceState second = receive(AVERAGE, first, "300", "13500.00");

        assertThat(first.avgUnitCost()).isEqualByComparingTo("42.500000");
        assertThat(second.onHand()).isEqualByComparingTo("600");
        assertThat(second.value()).isEqualByComparingTo("26250.00");
        assertThat(second.avgUnitCost()).as("(300 × 42.5 + 300 × 45) / 600").isEqualByComparingTo("43.750000");
        assertThat(second.layers()).isEmpty();
    }

    @Test
    void inv03IssuesTakeTheirShareOfTheValueAndTheLastOneTakesTheRest() {
        BalanceState cans = receive(AVERAGE, BalanceState.empty(), "195652.8", "138000.00");

        Costed oneCan = AVERAGE.issue(cans, d("19565.28"));
        Costed rest = AVERAGE.issue(oneCan.next(), d("176087.52"));

        assertThat(oneCan.cost())
                .as("a tenth of the value, signed like the quantity")
                .isEqualByComparingTo("-13800.00");
        assertThat(oneCan.next().value()).isEqualByComparingTo("124200.00");
        assertThat(rest.cost()).isEqualByComparingTo("-124200.00");
        assertThat(rest.next().value()).isEqualByComparingTo("0");
        assertThat(rest.next().onHand()).isEqualByComparingTo("0");
        assertThat(rest.next().avgUnitCost()).as("the last known cost stays").isEqualByComparingTo("0.705331");
    }

    @Test
    void inv03InventoryBelowZeroIsCostedAtTheLastCostAndTruedUpOnTheNextReceipt() {
        BalanceState start = receive(AVERAGE, BalanceState.empty(), "10", "425.00");
        Costed issued = AVERAGE.issue(start, d("16"));

        assertThat(issued.cost()).as("10 at their value, 6 more at 42.50").isEqualByComparingTo("-680.00");
        assertThat(issued.next().onHand()).isEqualByComparingTo("-6");
        assertThat(issued.next().value()).isEqualByComparingTo("-255.00");

        Costed received = AVERAGE.receive(issued.next(), d("30"), d("1350.00"), UUID.randomUUID(), NOW);

        assertThat(received.next().avgUnitCost()).as("the receipt's unit cost").isEqualByComparingTo("45");
        assertThat(received.next().onHand()).isEqualByComparingTo("24");
        assertThat(received.next().value()).isEqualByComparingTo("1080.00");
        assertThat(received.correction())
                .as("the 6 issued below zero cost 2.50 more each")
                .isEqualByComparingTo("-15.00");
        assertThat(AVERAGE.issue(BalanceState.empty(), d("1")).cost()).isEqualByComparingTo("0");
        assertThat(AVERAGE.valueAtCurrentCost(received.next(), d("2"))).isEqualByComparingTo("90.00");
    }

    @Test
    void inv03AReceiptThatLeavesTheBalanceAtZeroClearsItsValue() {
        Costed below = AVERAGE.issue(receive(AVERAGE, BalanceState.empty(), "1", "40.00"), d("3"));

        Costed exact = AVERAGE.receive(below.next(), d("2"), d("90.00"), UUID.randomUUID(), NOW);

        assertThat(exact.next().onHand()).isEqualByComparingTo("0");
        assertThat(exact.next().value()).isEqualByComparingTo("0");
        assertThat(exact.correction())
                .as("the 2 issued below zero cost 5.00 more each")
                .isEqualByComparingTo("-10.00");
    }

    @Test
    void inv04FifoIssuesConsumeTheOldestLayersFirst() {
        BalanceState first = receive(FIFO, BalanceState.empty(), "300", "12750.00");
        BalanceState both = receive(FIFO, first, "300", "13500.00");

        Costed issued = FIFO.issue(both, d("400"));

        assertThat(issued.cost()).as("300 × 42.50 + 100 × 45.00").isEqualByComparingTo("-17250.00");
        assertThat(issued.next().layers()).singleElement().satisfies(layer -> {
            assertThat(layer.qtyRemaining()).isEqualByComparingTo("200");
            assertThat(layer.valueRemaining()).isEqualByComparingTo("9000.00");
        });
        assertThat(issued.next().value()).isEqualByComparingTo("9000.00");
        assertThat(issued.next().avgUnitCost()).isEqualByComparingTo("45");
        assertThat(FIFO.valueAtCurrentCost(issued.next(), d("2"))).isEqualByComparingTo("90.00");
    }

    @Test
    void inv04FifoBelowZeroUsesTheLastCostAndIsTruedUpWithACorrection() {
        BalanceState start = receive(FIFO, BalanceState.empty(), "10", "425.00");
        Costed issued = FIFO.issue(start, d("16"));

        assertThat(issued.cost()).isEqualByComparingTo("-680.00");
        assertThat(issued.next().layers()).isEmpty();
        assertThat(issued.next().avgUnitCost()).as("last known cost").isEqualByComparingTo("42.5");

        Costed received = FIFO.receive(issued.next(), d("30"), d("1350.00"), UUID.randomUUID(), NOW);

        assertThat(received.correction()).isEqualByComparingTo("-15.00");
        assertThat(received.next().layers()).singleElement().satisfies(layer -> {
            assertThat(layer.qtyRemaining()).isEqualByComparingTo("24");
            assertThat(layer.valueRemaining()).isEqualByComparingTo("1080.00");
        });
        Costed still = FIFO.receive(FIFO.issue(start, d("20")).next(), d("4"), d("180.00"), UUID.randomUUID(), NOW);
        assertThat(still.next().onHand()).as("still below zero").isEqualByComparingTo("-6");
        assertThat(still.next().layers()).isEmpty();
        assertThat(still.next().value()).isEqualByComparingTo("-270.00");
    }

    @Test
    void inv04APartOfALayerTakesItsShareOfTheLayersValue() {
        BalanceState thirds = new BalanceState(
                d("3"),
                d("100.00"),
                d("33.333333"),
                List.of(new CostLayer(UUID.randomUUID(), d("3"), d("100.00"), d("33.333333"), NOW)));

        Costed one = FIFO.issue(thirds, d("1"));
        Costed two = FIFO.issue(one.next(), d("2"));

        assertThat(one.cost()).isEqualByComparingTo("-33.33");
        assertThat(two.cost()).as("the whole rest of the layer").isEqualByComparingTo("-66.67");
        assertThat(two.next().value()).isEqualByComparingTo("0");
        assertThat(FIFO.issue(BalanceState.empty(), d("1")).cost()).isEqualByComparingTo("0");
    }
}
