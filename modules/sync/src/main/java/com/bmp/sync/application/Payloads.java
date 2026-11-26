package com.bmp.sync.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The payloads of uploaded records, one per {@code *Upload} schema of {@code sync.yaml}. Missing required fields fail
 * while reading and the constraints (amounts with at most 4 decimals, quantities with at most 6, list sizes, text
 * lengths) are checked right after; either rejects the record with VALIDATION_FAILED.
 */
final class Payloads {

    private Payloads() {}

    record Line(
            UUID id,
            UUID variantId,
            UUID packagingUnitId,

            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 14, fraction = 6)
            BigDecimal qty,

            @Nullable Boolean weighed,

            @DecimalMin("0") @Digits(integer = 15, fraction = 4)
            BigDecimal unitPrice,

            @Nullable @DecimalMin("0") @Digits(integer = 15, fraction = 4)
            BigDecimal lineDiscount,

            @Nullable @DecimalMin("0") @Digits(integer = 15, fraction = 4)
            BigDecimal ruleDiscount,

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
            @Size(max = 20) String method,

            @DecimalMin("0") @Digits(integer = 15, fraction = 4)
            BigDecimal amount,

            @Nullable @DecimalMin("0") @Digits(integer = 15, fraction = 4)
            BigDecimal tendered,

            @Nullable @Size(max = 60) String reference) {

        Tender {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(amount, "amount");
        }
    }

    record Totals(
            @DecimalMin("0") @Digits(integer = 15, fraction = 4)
            BigDecimal subtotal,

            @DecimalMin("0") @Digits(integer = 15, fraction = 4)
            BigDecimal discountTotal,

            @DecimalMin("0") @Digits(integer = 15, fraction = 4)
            BigDecimal taxTotal,

            @DecimalMin("0") @Digits(integer = 15, fraction = 4)
            BigDecimal grandTotal) {

        Totals {
            Objects.requireNonNull(subtotal, "subtotal");
            Objects.requireNonNull(discountTotal, "discountTotal");
            Objects.requireNonNull(taxTotal, "taxTotal");
            Objects.requireNonNull(grandTotal, "grandTotal");
        }
    }

    record Discount(
            @Size(max = 20) String type,

            @DecimalMin("0") @Digits(integer = 15, fraction = 4)
            BigDecimal value,

            @Nullable @Size(max = 200) String reason,
            @Nullable UUID approvedBy) {

        Discount {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(value, "value");
        }
    }

    record Bill(
            UUID id,
            @Pattern(regexp = "^[A-Z0-9]{1,8}-[0-9]{6}$") String number,
            @Nullable UUID shiftId,
            @Size(max = 20) String channel,
            @Nullable UUID customerId,
            @Nullable UUID cashierId,
            @Size(min = 1, max = 500) List<@Valid Line> lines,
            @Nullable @Valid Discount billDiscount,
            @Size(min = 1, max = 20) List<@Valid Tender> tenders,
            @Valid Totals totals,
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

    record VoidBill(
            UUID billId,
            @Size(min = 1, max = 200) String reason,
            @Nullable UUID approvedBy,
            Instant voidedAt) {

        VoidBill {
            Objects.requireNonNull(billId, "billId");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(voidedAt, "voidedAt");
        }
    }

    record ReturnLine(
            UUID billLineId,

            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 14, fraction = 6)
            BigDecimal qty,

            boolean restock) {

        ReturnLine {
            Objects.requireNonNull(billLineId, "billLineId");
            Objects.requireNonNull(qty, "qty");
        }
    }

    record Refund(
            @Size(max = 20) String method,

            @DecimalMin("0") @Digits(integer = 15, fraction = 4)
            BigDecimal amount,

            @Nullable @Size(max = 60) String reference) {

        Refund {
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(amount, "amount");
        }
    }

    record ReturnBill(
            UUID billId,
            @Size(min = 1, max = 200) String reason,
            @Size(min = 1, max = 500) List<@Valid ReturnLine> lines,
            @Nullable @Size(min = 1, max = 20) List<@Valid Refund> refundTenders,
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

    record DrawerOpen(
            @DecimalMin("0") @Digits(integer = 15, fraction = 4)
            BigDecimal openingFloat,

            Instant openedAt) {

        DrawerOpen {
            Objects.requireNonNull(openingFloat, "openingFloat");
            Objects.requireNonNull(openedAt, "openedAt");
        }
    }

    record CashMovement(
            UUID sessionId,
            @Size(max = 20) String type,

            @DecimalMin("0") @Digits(integer = 15, fraction = 4)
            BigDecimal amount,

            @Size(min = 1, max = 200) String reason,
            Instant occurredAt) {

        CashMovement {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    record Denomination(
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 13, fraction = 2)
            BigDecimal value,

            @Min(0) int count) {

        Denomination {
            Objects.requireNonNull(value, "value");
        }
    }

    record DrawerCount(
            UUID sessionId, @Size(min = 1, max = 30) List<@Valid Denomination> denominations, Instant countedAt) {

        DrawerCount {
            Objects.requireNonNull(sessionId, "sessionId");
            denominations = List.copyOf(Objects.requireNonNull(denominations, "denominations"));
            Objects.requireNonNull(countedAt, "countedAt");
        }
    }

    record WastageLine(
            UUID variantId,
            @Nullable UUID packagingUnitId,
            @Nullable @Size(max = 30) String stockState,

            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 14, fraction = 6)
            BigDecimal qty) {

        WastageLine {
            Objects.requireNonNull(variantId, "variantId");
            Objects.requireNonNull(qty, "qty");
        }
    }

    record Wastage(
            @Size(max = 30) String reason,
            @Nullable @Size(max = 500) String note,
            @Size(min = 1, max = 500) List<@Valid WastageLine> lines,
            Instant occurredAt) {

        Wastage {
            Objects.requireNonNull(reason, "reason");
            lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }
}
