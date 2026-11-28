package com.bmp.finance.domain.service;

import com.bmp.finance.domain.error.FinanceErrorCode;
import com.bmp.finance.domain.model.OpenItem;
import com.bmp.finance.domain.model.Settlement;
import com.bmp.shared.error.BusinessRuleViolation;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rule FIN-07: an item settles the party's open items of the other sign — the documents it is allocated to first, then
 * the oldest due date (document date, then id, to break ties). What it cannot settle stays open on it, as a credit not
 * yet applied or an amount still owed.
 */
public final class OpenItemSettler {

    /** Items in the order they are settled when nothing is allocated. */
    public static final Comparator<OpenItem> OLDEST_FIRST = Comparator.comparing(OpenItem::dueDate)
            .thenComparing(OpenItem::documentDate)
            .thenComparing(OpenItem::id);

    private OpenItemSettler() {}

    /** An amount meant for one document's open items. */
    public record Allocation(UUID documentId, BigDecimal amount) {}

    /**
     * Settles {@code incoming} against {@code open}, changing both, and returns what was settled. Allocations are
     * followed as far as the items allow (money already taken is never refused); the rest goes oldest first.
     */
    public static List<Settlement> settle(OpenItem incoming, List<OpenItem> open, List<Allocation> allocations) {
        List<OpenItem> candidates = open.stream()
                .filter(item -> !item.id().equals(incoming.id()))
                .filter(item -> item.isOpen() && item.isDebit() != incoming.isDebit())
                .sorted(OLDEST_FIRST)
                .toList();
        List<Settlement> settled = new ArrayList<>();
        for (Allocation allocation : allocations) {
            BigDecimal left = allocation.amount();
            for (OpenItem target : candidates) {
                if (target.documentId().equals(allocation.documentId()) && left.signum() > 0) {
                    left = left.subtract(take(incoming, target, left, settled));
                }
            }
        }
        for (OpenItem target : candidates) {
            take(incoming, target, null, settled);
        }
        return settled;
    }

    private static BigDecimal take(
            OpenItem incoming,
            OpenItem target,
            @org.jspecify.annotations.Nullable BigDecimal cap,
            List<Settlement> settled) {
        BigDecimal amount = incoming.remaining().abs().min(target.remaining().abs());
        if (cap != null) {
            amount = amount.min(cap);
        }
        if (amount.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        incoming.settle(amount);
        target.settle(amount);
        settled.add(new Settlement(incoming.id(), target.id(), amount));
        return amount;
    }

    /**
     * Rule FIN-07 for payments entered through the API (FIN-11): each allocation names a document with items open on
     * the other side, takes at most what they have open, and together they take at most the payment.
     */
    public static void validate(
            BigDecimal payment, boolean paymentIsDebit, List<OpenItem> open, List<Allocation> allocations) {
        BigDecimal total = BigDecimal.ZERO;
        for (Allocation allocation : allocations) {
            BigDecimal available = open.stream()
                    .filter(item -> item.documentId().equals(allocation.documentId()))
                    .filter(item -> item.isOpen() && item.isDebit() != paymentIsDebit)
                    .map(item -> item.remaining().abs())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (available.signum() == 0) {
                throw new BusinessRuleViolation(
                        FinanceErrorCode.UNKNOWN_OPEN_ITEM, Map.of("documentId", allocation.documentId()));
            }
            if (allocation.amount().compareTo(available) > 0) {
                throw new BusinessRuleViolation(
                        FinanceErrorCode.ALLOCATION_EXCEEDS_OPEN,
                        Map.of(
                                "documentId",
                                allocation.documentId(),
                                "allocated",
                                allocation.amount().toPlainString(),
                                "open",
                                available.toPlainString()));
            }
            total = total.add(allocation.amount());
        }
        if (total.compareTo(payment) > 0) {
            throw new BusinessRuleViolation(
                    FinanceErrorCode.ALLOCATION_EXCEEDS_PAYMENT,
                    Map.of("allocated", total.toPlainString(), "payment", payment.toPlainString()));
        }
    }
}
