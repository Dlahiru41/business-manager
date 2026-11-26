package com.bmp.sales.application;

import com.bmp.sales.api.SalesCommands;
import com.bmp.sales.application.command.BillHandlers;
import com.bmp.sales.application.command.ShiftHandlers;
import com.bmp.sales.domain.error.SalesErrorCode;
import com.bmp.sales.domain.model.Bill;
import com.bmp.sales.domain.model.BillDiscount;
import com.bmp.sales.domain.model.DiscountType;
import com.bmp.sales.domain.model.Mode;
import com.bmp.sales.domain.model.SalesChannel;
import com.bmp.sales.domain.model.TenderMethod;
import com.bmp.sales.domain.model.Totals;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.PlatformErrorCode;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** {@link SalesCommands} over the offline paths of the bill and shift handlers (SYN-04). */
@Component
class SalesCommandsImpl implements SalesCommands {

    private final BillHandlers bills;
    private final ShiftHandlers shifts;

    SalesCommandsImpl(BillHandlers bills, ShiftHandlers shifts) {
        this.bills = bills;
        this.shifts = shifts;
    }

    @Override
    @Transactional
    public void openShift(UUID shiftId, UUID registerId, @Nullable UUID cashierId, Instant openedAt) {
        shifts.open(shiftId, registerId, openedAt, cashierId);
    }

    @Override
    @Transactional
    public void closeShift(UUID shiftId, UUID registerId, Instant closedAt) {
        shifts.registerOf(shiftId).filter(r -> !r.equals(registerId)).ifPresent(r -> {
            throw new BusinessRuleViolation(
                    SalesErrorCode.SHIFT_OF_ANOTHER_REGISTER, Map.of("shiftId", shiftId, "registerId", registerId));
        });
        if (shifts.isClosed(shiftId)) {
            return; // a till resending its shift close
        }
        shifts.close(shiftId, closedAt);
    }

    @Override
    @Transactional
    public void submitBill(OfflineBill b) {
        OfflineDiscount d = b.discount();
        bills.submit(
                new BillHandlers.SubmitBill(
                        b.id(),
                        b.number(),
                        b.registerId(),
                        b.shiftId(),
                        value(SalesChannel.class, b.channel(), "channel"),
                        b.customerId(),
                        b.lines().stream()
                                .map(l -> new LinePricer.LineInput(
                                        l.id(),
                                        l.variantId(),
                                        l.packagingUnitId(),
                                        l.qty(),
                                        l.weighed(),
                                        l.unitPrice(),
                                        l.lineDiscount(),
                                        l.ruleDiscount(),
                                        l.discountApprovedBy(),
                                        null))
                                .toList(),
                        d == null
                                ? null
                                : new BillDiscount(
                                        value(DiscountType.class, d.type(), "billDiscount.type"),
                                        d.value(),
                                        d.reason(),
                                        d.approvedBy()),
                        true,
                        b.tenders().stream()
                                .map(t -> new BillHandlers.TenderInput(
                                        t.id(),
                                        value(TenderMethod.class, t.method(), "tenders.method"),
                                        t.amount(),
                                        t.tendered(),
                                        t.reference()))
                                .toList(),
                        new Totals(
                                b.totals().subtotal(),
                                b.totals().discountTotal(),
                                b.totals().taxTotal(),
                                b.totals().grandTotal()),
                        b.completedAt(),
                        b.cashierId(),
                        b.deviceId(),
                        null),
                Mode.OFFLINE);
    }

    @Override
    @Transactional
    public void voidBill(UUID billId, UUID registerId, String reason, @Nullable UUID approvedBy, Instant voidedAt) {
        bills.voidBill(billId, registerId, reason, approvedBy, voidedAt, Mode.OFFLINE);
    }

    @Override
    @Transactional
    public void returnBill(OfflineReturn r) {
        bills.returnBill(
                r.billId(),
                new BillHandlers.ReturnBill(
                        r.creditNoteId(),
                        r.reason(),
                        r.lines().stream()
                                .map(l -> new Bill.ReturnLine(l.billLineId(), l.qty(), l.restock()))
                                .toList(),
                        r.refunds().stream()
                                .map(f -> new BillHandlers.RefundInput(
                                        value(TenderMethod.class, f.method(), "refundTenders.method"),
                                        f.amount(),
                                        f.reference()))
                                .toList(),
                        r.approvedBy(),
                        r.returnedAt(),
                        r.registerId()),
                Mode.OFFLINE);
    }

    private static <E extends Enum<E>> E value(Class<E> type, String name, String field) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleViolation(
                    PlatformErrorCode.VALIDATION_FAILED, "Unknown value " + name, Map.of("field", field));
        }
    }
}
