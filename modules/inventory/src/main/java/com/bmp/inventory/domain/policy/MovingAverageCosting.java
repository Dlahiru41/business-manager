package com.bmp.inventory.domain.policy;

import com.bmp.inventory.domain.model.BalanceState;
import com.bmp.shared.money.RoundingPolicy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Rule INV-03, the default: a receipt sets {@code avg = (onHand × avg + qty × unitCost) / (onHand + qty)}; when
 * nothing was on hand before (or less than nothing) the average becomes the receipt's unit cost, and the value of what
 * was issued below zero is trued up to it with a correction. Issues take their share of the balance's value, so
 * issuing everything takes the whole value.
 */
public final class MovingAverageCosting implements CostingPolicy {

    private final RoundingPolicy rounding;

    public MovingAverageCosting(RoundingPolicy rounding) {
        this.rounding = rounding;
    }

    @Override
    public Costed receive(BalanceState state, BigDecimal qty, BigDecimal cost, UUID layerId, Instant at) {
        BigDecimal onHand = state.onHand().add(qty);
        BigDecimal value = state.value().add(cost);
        if (state.onHand().signum() > 0) {
            return new Costed(
                    cost, BigDecimal.ZERO, new BalanceState(onHand, value, Costs.unitCost(value, onHand), List.of()));
        }
        BigDecimal unitCost = Costs.exactUnitCost(cost, qty);
        BigDecimal trueValue = rounding.round(onHand.multiply(unitCost));
        return new Costed(
                cost,
                trueValue.subtract(value),
                new BalanceState(onHand, trueValue, Costs.unitCost(cost, qty), List.of()));
    }

    @Override
    public Costed issue(BalanceState state, BigDecimal qty) {
        BigDecimal cost = issueCost(state, qty);
        BigDecimal onHand = state.onHand().subtract(qty);
        BigDecimal value = state.value().subtract(cost);
        BigDecimal avg = onHand.signum() > 0 ? Costs.unitCost(value, onHand) : state.avgUnitCost();
        return new Costed(cost.negate(), BigDecimal.ZERO, new BalanceState(onHand, value, avg, List.of()));
    }

    @Override
    public BigDecimal valueAtCurrentCost(BalanceState state, BigDecimal qty) {
        return rounding.round(qty.multiply(state.avgUnitCost()));
    }

    private BigDecimal issueCost(BalanceState state, BigDecimal qty) {
        BigDecimal onHand = state.onHand();
        if (onHand.signum() <= 0) {
            return rounding.round(qty.multiply(state.avgUnitCost()));
        }
        int covered = qty.compareTo(onHand);
        if (covered < 0) {
            return rounding.round(Costs.share(state.value(), qty, onHand));
        }
        BigDecimal beyond = rounding.round(qty.subtract(onHand).multiply(state.avgUnitCost()));
        return state.value().add(beyond);
    }
}
