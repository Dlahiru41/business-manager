package com.bmp.sales.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a till did offline, replayed by sync (SYN-04): bills are taken as the customer paid them and breaches are
 * flagged (SAL-03..SAL-07). Each command runs in the caller's transaction, or its own when there is none, and is
 * idempotent on the till's ids: a bill, shift or credit note seen before is not created again.
 */
public interface SalesCommands {

    void openShift(UUID shiftId, UUID registerId, @Nullable UUID cashierId, Instant openedAt);

    void closeShift(UUID shiftId, UUID registerId, Instant closedAt);

    void submitBill(OfflineBill bill);

    void voidBill(UUID billId, UUID registerId, String reason, @Nullable UUID approvedBy, Instant voidedAt);

    void returnBill(OfflineReturn returned);

    /** A bill as the till completed it. */
    record OfflineBill(
            UUID id,
            String number,
            UUID registerId,
            @Nullable UUID shiftId,
            String channel,
            @Nullable UUID customerId,
            @Nullable UUID cashierId,
            @Nullable UUID deviceId,
            List<OfflineLine> lines,
            @Nullable OfflineDiscount discount,
            List<OfflineTender> tenders,
            OfflineTotals totals,
            Instant completedAt) {

        public OfflineBill {
            lines = List.copyOf(lines);
            tenders = List.copyOf(tenders);
        }
    }

    record OfflineLine(
            UUID id,
            UUID variantId,
            UUID packagingUnitId,
            BigDecimal qty,
            boolean weighed,
            BigDecimal unitPrice,
            @Nullable BigDecimal lineDiscount,
            @Nullable BigDecimal ruleDiscount,
            @Nullable UUID discountApprovedBy) {}

    /**
     * A discount on the whole bill.
     *
     * @param type AMOUNT or PERCENT
     */
    record OfflineDiscount(
            String type,
            BigDecimal value,
            @Nullable String reason,
            @Nullable UUID approvedBy) {}

    record OfflineTender(
            UUID id,
            String method,
            BigDecimal amount,
            @Nullable BigDecimal tendered,
            @Nullable String reference) {}

    record OfflineTotals(BigDecimal subtotal, BigDecimal discountTotal, BigDecimal taxTotal, BigDecimal grandTotal) {}

    /** A return at the till's register {@code registerId}; {@code creditNoteId} is the till's id for it. */
    record OfflineReturn(
            UUID creditNoteId,
            UUID billId,
            UUID registerId,
            String reason,
            List<OfflineReturnLine> lines,
            List<OfflineRefund> refunds,
            @Nullable UUID approvedBy,
            Instant returnedAt) {

        public OfflineReturn {
            lines = List.copyOf(lines);
            refunds = List.copyOf(refunds);
        }
    }

    record OfflineReturnLine(UUID billLineId, BigDecimal qty, boolean restock) {}

    record OfflineRefund(
            String method, BigDecimal amount, @Nullable String reference) {}
}
