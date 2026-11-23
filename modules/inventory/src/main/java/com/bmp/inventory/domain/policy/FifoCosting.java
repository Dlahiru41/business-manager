package com.bmp.inventory.domain.policy;

import com.bmp.inventory.domain.model.BalanceState;
import com.bmp.inventory.domain.model.CostLayer;
import com.bmp.shared.money.RoundingPolicy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Rule INV-04 ({@code costingMethod=FIFO}): receipts add cost layers, issues consume the oldest first. Stock issued
 * below zero is costed at the last known cost and trued up on the next receipt with a correction.
 */
public final class FifoCosting implements CostingPolicy, FifoCostingMarker {

    private final RoundingPolicy rounding;

    public FifoCosting(RoundingPolicy rounding) {
        this.rounding = rounding;
    }

    @Override
    public Costed receive(BalanceState state, BigDecimal qty, BigDecimal cost, UUID layerId, Instant at) {
        BigDecimal onHand = state.onHand().add(qty);
        BigDecimal unitCost = Costs.unitCost(cost, qty);
        if (state.onHand().signum() >= 0) {
            List<CostLayer> layers = new ArrayList<>(state.layers());
            layers.add(new CostLayer(layerId, qty, cost, unitCost, at));
            BigDecimal value = state.value().add(cost);
            return new Costed(cost, BigDecimal.ZERO, new BalanceState(onHand, value, unitCost, layers));
        }
        // Below zero before: what was issued beyond the stock takes this receipt's cost.
        BigDecimal exact = Costs.exactUnitCost(cost, qty);
        BigDecimal trueValue = rounding.round(onHand.multiply(exact));
        List<CostLayer> layers =
                onHand.signum() > 0 ? List.of(new CostLayer(layerId, onHand, trueValue, unitCost, at)) : List.of();
        return new Costed(
                cost,
                trueValue.subtract(state.value().add(cost)),
                new BalanceState(onHand, trueValue, unitCost, layers));
    }

    @Override
    public Costed issue(BalanceState state, BigDecimal qty) {
        BigDecimal left = qty;
        BigDecimal cost = BigDecimal.ZERO;
        List<CostLayer> layers = new ArrayList<>();
        for (CostLayer layer : state.layers()) {
            if (left.signum() == 0) {
                layers.add(layer);
                continue;
            }
            BigDecimal taken = left.min(layer.qtyRemaining());
            BigDecimal taking = taken.compareTo(layer.qtyRemaining()) == 0
                    ? layer.valueRemaining()
                    : rounding.round(Costs.share(layer.valueRemaining(), taken, layer.qtyRemaining()));
            cost = cost.add(taking);
            left = left.subtract(taken);
            CostLayer rest = new CostLayer(
                    layer.id(),
                    layer.qtyRemaining().subtract(taken),
                    layer.valueRemaining().subtract(taking),
                    layer.unitCost(),
                    layer.receivedAt());
            if (!rest.isEmpty()) {
                layers.add(rest);
            }
        }
        BigDecimal lastCost = lastCost(state);
        if (left.signum() > 0) {
            cost = cost.add(rounding.round(left.multiply(lastCost)));
        }
        BigDecimal onHand = state.onHand().subtract(qty);
        BigDecimal value = state.value().subtract(cost);
        BigDecimal avg = onHand.signum() > 0 ? Costs.unitCost(value, onHand) : lastCost;
        return new Costed(cost.negate(), BigDecimal.ZERO, new BalanceState(onHand, value, avg, layers));
    }

    @Override
    public BigDecimal valueAtCurrentCost(BalanceState state, BigDecimal qty) {
        return rounding.round(qty.multiply(lastCost(state)));
    }

    /** The newest layer's cost, else the balance's last known cost. */
    private static BigDecimal lastCost(BalanceState state) {
        List<CostLayer> layers = state.layers();
        return layers.isEmpty() ? state.avgUnitCost() : layers.getLast().unitCost();
    }
}
