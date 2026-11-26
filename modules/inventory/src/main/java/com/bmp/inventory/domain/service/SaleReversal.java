package com.bmp.inventory.domain.service;

import com.bmp.inventory.domain.model.BalanceKey;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Stock coming back from a sale (T03-04): restocked returns and voids go back onto the balances the sale took the stock
 * from, at the cost it left with (not at today's cost), and never more than what is still out for the bill line.
 */
public final class SaleReversal {

    /** Scale of movement costs ({@code stock_movement.cost_amount numeric(19,4)}). */
    public static final int COST_SCALE = 4;

    private static final int UNIT_COST_SCALE = 6;

    private SaleReversal() {}

    /**
     * A movement already booked for a bill line.
     *
     * @param qtyBase negative for the SALE issue, positive for a SALE_RETURN or SALE_VOID
     * @param costAmount signed like {@code qtyBase}
     */
    public record Booked(UUID sourceLineId, BalanceKey key, BigDecimal qtyBase, BigDecimal costAmount) {}

    /** Stock to bring back onto a balance, at a total cost. */
    public record Back(UUID sourceLineId, BalanceKey key, BigDecimal qtyBase, BigDecimal cost) {}

    private record Slot(UUID line, BalanceKey key) {}

    /** What a line issued from one balance and what is still out of it. */
    private static final class Out {
        private BigDecimal soldQty = BigDecimal.ZERO;
        private BigDecimal soldCost = BigDecimal.ZERO;
        private BigDecimal qty = BigDecimal.ZERO;
        private BigDecimal cost = BigDecimal.ZERO;

        private BigDecimal unitCost() {
            return soldQty.signum() == 0
                    ? BigDecimal.ZERO
                    : soldCost.divide(soldQty, UNIT_COST_SCALE, RoundingMode.HALF_UP);
        }

        private BigDecimal costOf(BigDecimal take) {
            BigDecimal left = cost.max(BigDecimal.ZERO);
            if (take.compareTo(qty) == 0) {
                return left;
            }
            return unitCost()
                    .multiply(take)
                    .setScale(COST_SCALE, RoundingMode.HALF_UP)
                    .min(left);
        }
    }

    /** Whether the sale of the line was booked at all (its SALE movements exist). */
    public static boolean issued(List<Booked> booked, UUID line) {
        return booked.stream()
                .anyMatch(b -> b.sourceLineId().equals(line) && b.qtyBase().signum() < 0);
    }

    /**
     * Brings {@code qtyBase} of a line back: onto the balances the line left last first (undoing the issue), each up to
     * what is still out of it at its issue cost. More than went out cannot come through sales; if it does, the rest
     * goes onto the balance the line left last, at its issue cost, because the goods are physically back.
     */
    public static List<Back> restock(List<Booked> booked, UUID line, BigDecimal qtyBase) {
        List<Map.Entry<Slot, Out>> mine = new ArrayList<>(outstanding(booked).entrySet().stream()
                .filter(e -> e.getKey().line().equals(line))
                .toList());
        if (mine.isEmpty() || qtyBase.signum() <= 0) {
            return List.of();
        }
        java.util.Collections.reverse(mine);
        List<Back> back = new ArrayList<>();
        BigDecimal wanted = qtyBase;
        for (Map.Entry<Slot, Out> entry : mine) {
            Out out = entry.getValue();
            if (wanted.signum() == 0 || out.qty.signum() <= 0) {
                continue;
            }
            BigDecimal take = wanted.min(out.qty);
            back.add(new Back(line, entry.getKey().key(), take, out.costOf(take)));
            wanted = wanted.subtract(take);
        }
        if (wanted.signum() > 0) {
            Map.Entry<Slot, Out> last = mine.getFirst();
            back.add(new Back(
                    line,
                    last.getKey().key(),
                    wanted,
                    last.getValue().unitCost().multiply(wanted).setScale(COST_SCALE, RoundingMode.HALF_UP)));
        }
        return back;
    }

    /** Reverses a bill: everything still out of every line and balance, at the cost it left with. */
    public static List<Back> reverseAll(List<Booked> booked) {
        List<Back> back = new ArrayList<>();
        outstanding(booked).forEach((slot, out) -> {
            if (out.qty.signum() > 0) {
                back.add(new Back(slot.line(), slot.key(), out.qty, out.costOf(out.qty)));
            }
        });
        return back;
    }

    /** Per line and balance, in the order the sale issued them. */
    private static Map<Slot, Out> outstanding(List<Booked> booked) {
        Map<Slot, Out> slots = new LinkedHashMap<>();
        for (Booked b : booked) {
            Out out = slots.computeIfAbsent(new Slot(b.sourceLineId(), b.key()), s -> new Out());
            if (b.qtyBase().signum() < 0) {
                out.soldQty = out.soldQty.add(b.qtyBase().negate());
                out.soldCost = out.soldCost.add(b.costAmount().negate());
                out.qty = out.qty.add(b.qtyBase().negate());
                out.cost = out.cost.add(b.costAmount().negate());
            } else {
                out.qty = out.qty.subtract(b.qtyBase());
                out.cost = out.cost.subtract(b.costAmount());
            }
        }
        return slots;
    }
}
