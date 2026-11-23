package com.bmp.inventory.domain.service;

import static com.bmp.inventory.domain.InventoryFixtures.EGGS;
import static com.bmp.inventory.domain.InventoryFixtures.ROUNDING;
import static com.bmp.inventory.domain.InventoryFixtures.ids;
import static com.bmp.inventory.domain.InventoryFixtures.in;
import static com.bmp.inventory.domain.InventoryFixtures.out;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.inventory.domain.error.InventoryErrorCode;
import com.bmp.inventory.domain.model.BalanceState;
import com.bmp.inventory.domain.model.MovementType;
import com.bmp.inventory.domain.model.StockFlag;
import com.bmp.inventory.domain.policy.CostingMethod;
import com.bmp.inventory.domain.policy.CostingPolicy;
import com.bmp.shared.error.BusinessRuleViolation;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StockPlannerTest {

    private static final CostingPolicy AVERAGE = CostingMethod.MOVING_AVERAGE.policy(ROUNDING);

    @Test
    void inv01Inv02MovementsAreSignedAndCostedLikeTheirQuantity() {
        StockPlanner.Plan plan =
                StockPlanner.plan(Map.of(), List.of(in(EGGS, "300", "12750.00"), out(EGGS, "6", true)), AVERAGE, ids());

        assertThat(plan.movements()).hasSize(2);
        assertThat(plan.movements().get(0).costAmount()).isEqualByComparingTo("12750.00");
        assertThat(plan.movements().get(0).unitCost()).isEqualByComparingTo("42.500000");
        assertThat(plan.movements().get(1).qtyBase()).isEqualByComparingTo("-6");
        assertThat(plan.movements().get(1).costAmount()).isEqualByComparingTo("-255.00");
        assertThat(plan.movements().get(1).flags()).isEmpty();
        BalanceState after = plan.balances().get(EGGS);
        assertThat(after.onHand()).isEqualByComparingTo("294");
        assertThat(after.value())
                .as("the balance is the sum of its movements")
                .isEqualByComparingTo(
                        plan.movements().stream().map(m -> m.costAmount()).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @Test
    void inv07SalesNeverBlockButFlagNegativeStock() {
        StockPlanner.Plan plan = StockPlanner.plan(Map.of(), List.of(out(EGGS, "5", true)), AVERAGE, ids());

        assertThat(plan.movements()).singleElement().satisfies(m -> {
            assertThat(m.flags()).containsExactly(StockFlag.NEGATIVE_STOCK);
            assertThat(m.qtyBase()).isEqualByComparingTo("-5");
        });
        assertThat(plan.balances().get(EGGS).onHand()).isEqualByComparingTo("-5");
    }

    @Test
    void inv08ABlockingPolicyRefusesToGoBelowZero() {
        assertThatThrownBy(() -> StockPlanner.plan(
                        Map.of(EGGS, BalanceState.empty()),
                        List.of(in(EGGS, "4", "170.00"), out(EGGS, "5", false)),
                        AVERAGE,
                        ids()))
                .isInstanceOfSatisfying(BusinessRuleViolation.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(InventoryErrorCode.INSUFFICIENT_STOCK);
                    assertThat(e.details()).containsEntry("requested", "5").containsEntry("available", "4");
                });
        assertThat(StockPlanner.plan(Map.of(), List.of(in(EGGS, "5", "1.00"), out(EGGS, "5", false)), AVERAGE, ids())
                        .balances()
                        .get(EGGS)
                        .onHand())
                .as("exactly what is there may go")
                .isEqualByComparingTo("0");
    }

    @Test
    void inv03AReceiptAfterNegativeStockAddsACorrection() {
        StockPlanner.Plan plan = StockPlanner.plan(
                Map.of(),
                List.of(in(EGGS, "10", "425.00"), out(EGGS, "16", true), in(EGGS, "30", "1350.00")),
                AVERAGE,
                ids());

        assertThat(plan.movements())
                .extracting(PlannedMovement::type)
                .containsExactly(
                        MovementType.RECEIPT, MovementType.SALE, MovementType.RECEIPT, MovementType.CORRECTION);
        PlannedMovement correction = plan.movements().getLast();
        assertThat(correction.isCorrection()).isTrue();
        assertThat(correction.qtyBase()).isEqualByComparingTo("0");
        assertThat(correction.costAmount()).isEqualByComparingTo("-15.00");
        assertThat(correction.reasonCode()).isEqualTo("NEGATIVE_STOCK_TRUE_UP");
        assertThat(plan.balances().get(EGGS).value()).isEqualByComparingTo("1080.00");
    }

    @Test
    void incomingStockWithoutACostTakesTheCurrentCost() {
        StockPlanner.Plan plan =
                StockPlanner.plan(Map.of(), List.of(in(EGGS, "10", "425.00"), in(EGGS, "2", null)), AVERAGE, ids());

        assertThat(plan.movements().get(1).costAmount()).isEqualByComparingTo("85.00");
        assertThat(plan.movements().get(1).isCorrection()).isFalse();
    }

    @Test
    void linesMoveStockAndCostIsNeverNegative() {
        assertThatThrownBy(() -> in(EGGS, "0", "1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> in(EGGS, "1", "-1")).isInstanceOf(IllegalArgumentException.class);
        assertThat(in(EGGS, "1", null).isInbound()).isTrue();
        assertThat(out(EGGS, "1", true).isInbound()).isFalse();
    }

    @Test
    void inv13OneAlertPerCrossingOfTheReorderLevel() {
        BigDecimal level = new BigDecimal("120");

        assertThat(ReorderRule.decide(new BigDecimal("119"), level, false, true))
                .isEqualTo(ReorderRule.Decision.RAISE);
        assertThat(ReorderRule.decide(new BigDecimal("120"), level, false, true))
                .isEqualTo(ReorderRule.Decision.RAISE);
        assertThat(ReorderRule.decide(new BigDecimal("100"), level, true, true))
                .as("already flagged")
                .isEqualTo(ReorderRule.Decision.KEEP);
        assertThat(ReorderRule.decide(new BigDecimal("100"), level, false, false))
                .as("only out-movements raise it")
                .isEqualTo(ReorderRule.Decision.KEEP);
        assertThat(ReorderRule.decide(new BigDecimal("121"), level, true, false))
                .isEqualTo(ReorderRule.Decision.CLEAR);
        assertThat(ReorderRule.decide(new BigDecimal("121"), level, false, true))
                .isEqualTo(ReorderRule.Decision.KEEP);
    }
}
