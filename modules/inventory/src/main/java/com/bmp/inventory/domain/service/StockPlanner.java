package com.bmp.inventory.domain.service;

import com.bmp.inventory.domain.error.InventoryErrorCode;
import com.bmp.inventory.domain.model.BalanceKey;
import com.bmp.inventory.domain.model.BalanceState;
import com.bmp.inventory.domain.model.MovementType;
import com.bmp.inventory.domain.model.StockFlag;
import com.bmp.inventory.domain.policy.Costed;
import com.bmp.inventory.domain.policy.CostingPolicy;
import com.bmp.shared.error.BusinessRuleViolation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Plans stock lines into ledger movements, in order, against the balances they move (INV-01..INV-04, INV-07,
 * INV-08): costs incoming stock as given (or at current cost), outgoing stock at current cost, flags or refuses
 * negative stock, and adds a CORRECTION after a receipt that trued up negative stock.
 */
public final class StockPlanner {

    private static final int UNIT_COST_SCALE = 6;

    private StockPlanner() {}

    /**
     * The movements of the lines and the balances after them.
     *
     * @param balances the locked balances of every key of the lines, as they are now
     */
    public record Plan(List<PlannedMovement> movements, Map<BalanceKey, BalanceState> balances) {

        public Plan {
            movements = List.copyOf(movements);
            balances = Map.copyOf(balances);
        }
    }

    public static Plan plan(
            Map<BalanceKey, BalanceState> balances, List<StockLine> lines, CostingPolicy costing, Supplier<UUID> ids) {
        Map<BalanceKey, BalanceState> states = new LinkedHashMap<>(balances);
        List<PlannedMovement> movements = new ArrayList<>();
        for (StockLine line : lines) {
            BalanceState state = states.getOrDefault(line.key(), BalanceState.empty());
            UUID id = ids.get();
            Costed costed;
            if (line.isInbound()) {
                BigDecimal cost = line.cost() != null ? line.cost() : costing.valueAtCurrentCost(state, line.qtyBase());
                costed = costing.receive(state, line.qtyBase(), cost, id, line.occurredAt());
            } else {
                BigDecimal qty = line.qtyBase().negate();
                if (!line.mayGoNegative() && state.onHand().compareTo(qty) < 0) {
                    throw new BusinessRuleViolation(
                            InventoryErrorCode.INSUFFICIENT_STOCK,
                            Map.of(
                                    "variantId", line.key().variantId(),
                                    "stockState", line.key().stockState(),
                                    "requested", qty.stripTrailingZeros().toPlainString(),
                                    "available",
                                            state.onHand().stripTrailingZeros().toPlainString()));
                }
                costed = costing.issue(state, qty);
            }
            Set<StockFlag> flags = !line.isInbound() && costed.next().onHand().signum() < 0
                    ? EnumSet.of(StockFlag.NEGATIVE_STOCK)
                    : EnumSet.noneOf(StockFlag.class);
            movements.add(new PlannedMovement(
                    id,
                    line.key(),
                    line.type(),
                    line.qtyBase(),
                    costed.cost().abs().divide(line.qtyBase().abs(), UNIT_COST_SCALE, RoundingMode.HALF_UP),
                    costed.cost(),
                    flags,
                    line.sourceType(),
                    line.sourceId(),
                    line.sourceLineId(),
                    line.reasonCode(),
                    line.occurredAt()));
            if (costed.correction().signum() != 0) {
                movements.add(new PlannedMovement(
                        ids.get(),
                        line.key(),
                        MovementType.CORRECTION,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        costed.correction(),
                        Set.of(),
                        line.sourceType(),
                        line.sourceId(),
                        line.sourceLineId(),
                        "NEGATIVE_STOCK_TRUE_UP",
                        line.occurredAt()));
            }
            states.put(line.key(), costed.next());
        }
        return new Plan(movements, states);
    }
}
