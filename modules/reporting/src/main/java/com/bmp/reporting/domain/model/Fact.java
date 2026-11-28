package com.bmp.reporting.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What reporting keeps of an event (ADR-033 §1): plain ids, decimals and dates, so the event log can be read back
 * whatever the publishing module's records become. Every fact is applied to the read models the same way live and in a
 * rebuild.
 */
public sealed interface Fact {

    /** A completed bill; revenue per line is net of discounts and tax. */
    record Sale(
            UUID billId,
            String number,
            LocalDate businessDate,
            UUID locationId,
            UUID registerId,
            String channel,
            @Nullable UUID customerId,
            @Nullable UUID cashierId,
            int hour,
            Instant completedAt,
            BigDecimal grandTotal,
            BigDecimal taxTotal,
            BigDecimal discountTotal,
            List<SaleLine> lines)
            implements Fact {

        public Sale {
            lines = List.copyOf(lines);
        }
    }

    record SaleLine(
            UUID lineId,
            UUID variantId,
            UUID packagingUnitId,
            BigDecimal qty,
            BigDecimal qtyBase,
            BigDecimal revenue,
            BigDecimal discount,
            BigDecimal tax) {}

    /**
     * A credit note.
     *
     * @param moneyRefund what it should give back in money (CREDIT refunds excluded), watched by REP-07
     */
    record Return(
            UUID creditNoteId,
            UUID billId,
            LocalDate businessDate,
            UUID locationId,
            Instant returnedAt,
            BigDecimal moneyRefund,
            List<ReturnLine> lines)
            implements Fact {

        public Return {
            lines = List.copyOf(lines);
        }
    }

    /** A returned bill line; revenue is the amount less its tax. */
    record ReturnLine(
            UUID billLineId, UUID variantId, BigDecimal qty, BigDecimal qtyBase, BigDecimal revenue, BigDecimal tax) {}

    /** A voided bill: every line sold comes off on the day of the void. */
    record Voided(UUID billId, LocalDate businessDate) implements Fact {}

    /**
     * The cost inventory issued for bill lines (positive), or brought back by a return or void (negative).
     *
     * @param kind SALE, SALE_RETURN or SALE_VOID
     * @param sourceId the bill, or the credit note of a return
     */
    record Cost(String kind, UUID sourceId, UUID billId, List<LineCost> lines) implements Fact {

        public Cost {
            lines = List.copyOf(lines);
        }
    }

    record LineCost(UUID billLineId, BigDecimal cost) {}

    /**
     * Money taken (positive) or given back (negative).
     *
     * @param kind CAPTURE or REFUND
     */
    record Payment(
            UUID entryId,
            String kind,
            LocalDate businessDate,
            String method,
            BigDecimal amount,
            @Nullable UUID registerId,
            @Nullable UUID billId,
            @Nullable UUID creditNoteId)
            implements Fact {}

    record Expense(UUID expenseId, LocalDate businessDate, UUID categoryId, BigDecimal amount) implements Fact {}

    record Drawer(
            UUID sessionId,
            UUID registerId,
            LocalDate businessDate,
            BigDecimal expected,
            BigDecimal counted,
            BigDecimal variance,
            Instant reconciledAt)
            implements Fact {}

    /**
     * Stock moved by an adjustment, a count or a transformation, at cost (signed like the quantity).
     *
     * @param source ADJUSTMENT, COUNT or TRANSFORMATION
     */
    record Movement(
            String source,
            LocalDate businessDate,
            UUID locationId,
            @Nullable String reason,
            List<MovementLine> lines) implements Fact {

        public Movement {
            lines = List.copyOf(lines);
        }
    }

    record MovementLine(UUID movementId, UUID variantId, BigDecimal qtyBase, BigDecimal cost) {}

    record VariantNamed(UUID variantId, UUID productId, String sku, String name, long version) implements Fact {}

    record ProductNamed(
            UUID productId, String name, @Nullable UUID categoryId, long version) implements Fact {}
}
