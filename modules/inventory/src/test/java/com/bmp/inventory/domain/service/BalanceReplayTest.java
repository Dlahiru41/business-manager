package com.bmp.inventory.domain.service;

import static com.bmp.inventory.domain.InventoryFixtures.EGGS;
import static com.bmp.inventory.domain.InventoryFixtures.ROUNDING;
import static com.bmp.inventory.domain.InventoryFixtures.ids;
import static com.bmp.inventory.domain.InventoryFixtures.in;
import static com.bmp.inventory.domain.InventoryFixtures.out;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.inventory.domain.model.BalanceState;
import com.bmp.inventory.domain.model.MovementType;
import com.bmp.inventory.domain.policy.CostingMethod;
import com.bmp.inventory.domain.policy.CostingPolicy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BalanceReplayTest {

    /** Posts the lines one posting at a time, as the ledger does, and returns the recorded rows and final state. */
    static List<BalanceReplay.Recorded> post(
            List<com.bmp.inventory.domain.service.StockLine> lines,
            CostingPolicy costing,
            Map<com.bmp.inventory.domain.model.BalanceKey, BalanceState> states) {
        Supplier<UUID> ids = ids();
        List<BalanceReplay.Recorded> rows = new ArrayList<>();
        for (var line : lines) {
            StockPlanner.Plan plan = StockPlanner.plan(states, List.of(line), costing, ids);
            plan.movements()
                    .forEach(m -> rows.add(
                            new BalanceReplay.Recorded(m.id(), m.type(), m.qtyBase(), m.costAmount(), m.occurredAt())));
            states.putAll(plan.balances());
        }
        return rows;
    }

    @ParameterizedTest
    @EnumSource(CostingMethod.class)
    void inv16ReplayingTheLedgerGivesTheBalanceThePostingsLeft(CostingMethod method) {
        CostingPolicy costing = method.policy(ROUNDING);
        Map<com.bmp.inventory.domain.model.BalanceKey, BalanceState> states = new java.util.HashMap<>();
        List<BalanceReplay.Recorded> rows = post(
                List.of(
                        in(EGGS, "3", "10.00"),
                        out(EGGS, "1", true),
                        in(EGGS, "30", "1275.00"),
                        out(EGGS, "40", true),
                        in(EGGS, "60", "2580.00"),
                        out(EGGS, "7", true),
                        in(EGGS, "1", null)),
                costing,
                states);

        BalanceState replayed = BalanceReplay.rebuild(rows, costing);
        BalanceState posted = states.get(EGGS);

        assertThat(rows)
                .as("the receipt after the shortfall trued it up")
                .anyMatch(r -> r.type() == MovementType.CORRECTION);
        assertThat(BalanceReplay.same(replayed, posted))
                .as("%s vs %s", replayed, posted)
                .isTrue();
        assertThat(replayed.layers()).isEqualTo(posted.layers());
        assertThat(replayed.value())
                .as("the value is the sum of the costs")
                .isEqualByComparingTo(
                        rows.stream().map(BalanceReplay.Recorded::costAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @Test
    void balancesThatDifferAreNotTheSame() {
        BalanceState a =
                new BalanceState(BigDecimal.ONE, new BigDecimal("3.33"), new BigDecimal("3.333333"), List.of());

        assertThat(BalanceReplay.same(
                        a,
                        new BalanceState(
                                new BigDecimal("1.000"),
                                new BigDecimal("3.330"),
                                new BigDecimal("3.3333330"),
                                List.of())))
                .isTrue();
        assertThat(BalanceReplay.same(a, new BalanceState(BigDecimal.TWO, a.value(), a.avgUnitCost(), List.of())))
                .isFalse();
        assertThat(BalanceReplay.same(a, new BalanceState(a.onHand(), BigDecimal.TEN, a.avgUnitCost(), List.of())))
                .isFalse();
        assertThat(BalanceReplay.same(a, new BalanceState(a.onHand(), a.value(), BigDecimal.TEN, List.of())))
                .isFalse();
        assertThat(BalanceReplay.replay(List.of(), CostingMethod.FIFO.policy(ROUNDING)))
                .isEqualTo(BalanceState.empty());
    }
}
