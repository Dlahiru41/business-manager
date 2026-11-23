package com.bmp.inventory.domain.service;

import com.bmp.inventory.domain.model.BalanceState;
import com.bmp.inventory.domain.model.MovementType;
import com.bmp.inventory.domain.policy.CostingPolicy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Rule INV-16: a balance recomputed from its ledger rows. On hand and value are the sums of the rows (CORRECTION rows
 * included), exactly what the ledger says. Only the average cost (moving average) and the cost layers (FIFO), which are
 * not sums, come from replaying the rows in posting order through the costing policy; there CORRECTION rows are skipped
 * because the receipt before them already trues up the value.
 */
public final class BalanceReplay {

    private BalanceReplay() {}

    /** A ledger row as recorded, in posting order. */
    public record Recorded(UUID id, MovementType type, BigDecimal qtyBase, BigDecimal costAmount, Instant occurredAt) {}

    /** The balance the rows make: their sums, with the replayed average cost and layers. */
    public static BalanceState rebuild(List<Recorded> rows, CostingPolicy costing) {
        BalanceState replayed = replay(rows, costing);
        BigDecimal onHand = rows.stream().map(Recorded::qtyBase).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal value = rows.stream().map(Recorded::costAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new BalanceState(onHand, value, replayed.avgUnitCost(), replayed.layers());
    }

    public static BalanceState replay(List<Recorded> rows, CostingPolicy costing) {
        BalanceState state = BalanceState.empty();
        for (Recorded row : rows) {
            if (row.type() == MovementType.CORRECTION) {
                continue;
            }
            state = row.qtyBase().signum() > 0
                    ? costing.receive(state, row.qtyBase(), row.costAmount(), row.id(), row.occurredAt())
                            .next()
                    : costing.issue(state, row.qtyBase().negate()).next();
        }
        return state;
    }

    /** Whether two balances hold the same stock at the same value and unit cost. */
    public static boolean same(BalanceState a, BalanceState b) {
        return a.onHand().compareTo(b.onHand()) == 0
                && a.value().compareTo(b.value()) == 0
                && a.avgUnitCost().compareTo(b.avgUnitCost()) == 0;
    }
}
