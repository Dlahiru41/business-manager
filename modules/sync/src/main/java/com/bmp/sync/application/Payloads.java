package com.bmp.sync.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The payloads of uploaded records, one per {@code *Upload} schema of {@code sync.yaml}. Missing required fields fail
 * while reading (the record is rejected with VALIDATION_FAILED).
 */
final class Payloads {

    private Payloads() {}

    record Line(
            UUID id,
            UUID variantId,
            UUID packagingUnitId,
            BigDecimal qty,
            @Nullable Boolean weighed,
            BigDecimal unitPrice,
            @Nullable BigDecimal lineDiscount,
            @Nullable BigDecimal ruleDiscount,
            @Nullable UUID discountApprovedBy) {

        Line {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(variantId, "variantId");
            Objects.requireNonNull(packagingUnitId, "packagingUnitId");
            Objects.requireNonNull(qty, "qty");
            Objects.requireNonNull(unitPrice, "unitPrice");
        }
    }

    record Tender(
            UUID id,
            String method,
            BigDecimal amount,
            @Nullable BigDecimal tendered,
            @Nullable String reference) {

        Tender {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(amount, "amount");
        }
    }

    record Totals(BigDecimal subtotal, BigDecimal discountTotal, BigDecimal taxTotal, BigDecimal grandTotal) {

        Totals {
            Objects.requireNonNull(subtotal, "subtotal");
            Objects.requireNonNull(discountTotal, "discountTotal");
            Objects.requireNonNull(taxTotal, "taxTotal");
            Objects.requireNonNull(grandTotal, "grandTotal");
        }
    }

    record Discount(
            String type,
            BigDecimal value,
            @Nullable String reason,
            @Nullable UUID approvedBy) {

        Discount {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(value, "value");
        }
    }

    record Bill(
            UUID id,
            String number,
            @Nullable UUID shiftId,
            String channel,
            @Nullable UUID customerId,
            @Nullable UUID cashierId,
            @Nullable UUID priceListId,
            @Nullable Integer priceListVersion,
            List<Line> lines,
            @Nullable Discount billDiscount,
            List<Tender> tenders,
            Totals totals,
            Instant completedAt) {

        Bill {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(number, "number");
            Objects.requireNonNull(channel, "channel");
            lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
            tenders = List.copyOf(Objects.requireNonNull(tenders, "tenders"));
            Objects.requireNonNull(totals, "totals");
            Objects.requireNonNull(completedAt, "completedAt");
        }
    }

    record VoidBill(UUID billId, String reason, @Nullable UUID approvedBy, Instant voidedAt) {

        VoidBill {
            Objects.requireNonNull(billId, "billId");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(voidedAt, "voidedAt");
        }
    }

    record ReturnLine(UUID billLineId, BigDecimal qty, boolean restock) {

        ReturnLine {
            Objects.requireNonNull(billLineId, "billLineId");
            Objects.requireNonNull(qty, "qty");
        }
    }

    record Refund(
            String method, BigDecimal amount, @Nullable String reference) {

        Refund {
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(amount, "amount");
        }
    }

    record ReturnBill(
            UUID billId,
            String reason,
            List<ReturnLine> lines,
            @Nullable List<Refund> refundTenders,
            @Nullable UUID approvedBy,
            Instant returnedAt) {

        ReturnBill {
            Objects.requireNonNull(billId, "billId");
            Objects.requireNonNull(reason, "reason");
            lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
            Objects.requireNonNull(returnedAt, "returnedAt");
        }
    }

    record ShiftOpen(@Nullable UUID cashierId, Instant openedAt) {

        ShiftOpen {
            Objects.requireNonNull(openedAt, "openedAt");
        }
    }

    record ShiftClose(UUID shiftId, Instant closedAt) {

        ShiftClose {
            Objects.requireNonNull(shiftId, "shiftId");
            Objects.requireNonNull(closedAt, "closedAt");
        }
    }

    record DrawerOpen(BigDecimal openingFloat, Instant openedAt) {

        DrawerOpen {
            Objects.requireNonNull(openingFloat, "openingFloat");
            Objects.requireNonNull(openedAt, "openedAt");
        }
    }

    record CashMovement(UUID sessionId, String type, BigDecimal amount, String reason, Instant occurredAt) {

        CashMovement {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    record Denomination(BigDecimal value, int count) {

        Denomination {
            Objects.requireNonNull(value, "value");
        }
    }

    record DrawerCount(UUID sessionId, List<Denomination> denominations, Instant countedAt) {

        DrawerCount {
            Objects.requireNonNull(sessionId, "sessionId");
            denominations = List.copyOf(Objects.requireNonNull(denominations, "denominations"));
            Objects.requireNonNull(countedAt, "countedAt");
        }
    }

    record WastageLine(
            UUID variantId,
            @Nullable UUID packagingUnitId,
            @Nullable String stockState,
            BigDecimal qty) {

        WastageLine {
            Objects.requireNonNull(variantId, "variantId");
            Objects.requireNonNull(qty, "qty");
        }
    }

    record Wastage(String reason, @Nullable String note, List<WastageLine> lines, Instant occurredAt) {

        Wastage {
            Objects.requireNonNull(reason, "reason");
            lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }
}
