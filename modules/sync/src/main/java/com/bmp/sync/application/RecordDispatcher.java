package com.bmp.sync.application;

import com.bmp.inventory.api.InventoryCommands;
import com.bmp.payments.api.PaymentCommands;
import com.bmp.sales.api.SalesCommands;
import com.bmp.sync.domain.model.UploadRecordType;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Rule SYN-04: hands each uploaded record to the module that owns it, through that module's API. The record's id is
 * the id of what it creates (a shift, a drawer, a credit note, a cash movement), so the owning module recognises a
 * replay too. Every record acts for the till's register (SYN-01): its shifts and drawer, and bills of its location.
 */
@Component
class RecordDispatcher {

    private final SalesCommands sales;
    private final PaymentCommands payments;
    private final InventoryCommands inventory;
    private final JsonMapper json;

    RecordDispatcher(SalesCommands sales, PaymentCommands payments, InventoryCommands inventory, JsonMapper json) {
        this.sales = sales;
        this.payments = payments;
        this.inventory = inventory;
        this.json = json;
    }

    void dispatch(SyncDevices.Till till, UploadRecordType type, UUID id, JsonNode payload) {
        switch (type) {
            case SHIFT_OPEN -> {
                var p = read(payload, Payloads.ShiftOpen.class);
                sales.openShift(id, till.registerId(), p.cashierId(), p.openedAt());
            }
            case SHIFT_CLOSE -> {
                var p = read(payload, Payloads.ShiftClose.class);
                sales.closeShift(p.shiftId(), till.registerId(), p.closedAt());
            }
            case BILL -> sales.submitBill(bill(till, read(payload, Payloads.Bill.class)));
            case VOID -> {
                var p = read(payload, Payloads.VoidBill.class);
                sales.voidBill(p.billId(), till.registerId(), p.reason(), p.approvedBy(), p.voidedAt());
            }
            case RETURN -> sales.returnBill(returned(id, till, read(payload, Payloads.ReturnBill.class)));
            case DRAWER_OPEN -> {
                var p = read(payload, Payloads.DrawerOpen.class);
                payments.openDrawer(
                        new PaymentCommands.OpenDrawer(id, till.registerId(), p.openingFloat(), p.openedAt(), null));
            }
            case CASH_MOVEMENT -> {
                var p = read(payload, Payloads.CashMovement.class);
                payments.recordCashMovement(new PaymentCommands.CashMovementCommand(
                        id, p.sessionId(), till.registerId(), p.type(), p.amount(), p.reason(), p.occurredAt()));
            }
            case DRAWER_COUNT -> {
                var p = read(payload, Payloads.DrawerCount.class);
                payments.countDrawer(new PaymentCommands.CountDrawer(
                        p.sessionId(),
                        till.registerId(),
                        p.denominations().stream()
                                .map(d -> new PaymentCommands.Denomination(d.value(), d.count()))
                                .toList(),
                        p.countedAt(),
                        null));
            }
            case WASTAGE -> {
                var p = read(payload, Payloads.Wastage.class);
                inventory.recordWastage(new InventoryCommands.Wastage(
                        till.locationId(),
                        p.reason(),
                        p.note(),
                        p.occurredAt(),
                        p.lines().stream()
                                .map(l -> new InventoryCommands.WastageLine(
                                        l.variantId(), l.packagingUnitId(), l.stockState(), l.qty()))
                                .toList()));
            }
        }
    }

    private <T> T read(JsonNode payload, Class<T> type) {
        return json.treeToValue(payload, type);
    }

    private static SalesCommands.OfflineBill bill(SyncDevices.Till till, Payloads.Bill b) {
        Payloads.Discount d = b.billDiscount();
        return new SalesCommands.OfflineBill(
                b.id(),
                b.number(),
                till.registerId(),
                b.shiftId(),
                b.channel(),
                b.customerId(),
                b.cashierId(),
                till.deviceId(),
                b.lines().stream()
                        .map(l -> new SalesCommands.OfflineLine(
                                l.id(),
                                l.variantId(),
                                l.packagingUnitId(),
                                l.qty(),
                                Boolean.TRUE.equals(l.weighed()),
                                l.unitPrice(),
                                l.lineDiscount(),
                                l.ruleDiscount(),
                                l.discountApprovedBy()))
                        .toList(),
                d == null ? null : new SalesCommands.OfflineDiscount(d.type(), d.value(), d.reason(), d.approvedBy()),
                b.tenders().stream()
                        .map(t -> new SalesCommands.OfflineTender(
                                t.id(), t.method(), t.amount(), t.tendered(), t.reference()))
                        .toList(),
                new SalesCommands.OfflineTotals(
                        b.totals().subtotal(),
                        b.totals().discountTotal(),
                        b.totals().taxTotal(),
                        b.totals().grandTotal()),
                b.completedAt());
    }

    private static SalesCommands.OfflineReturn returned(
            UUID creditNoteId, SyncDevices.Till till, Payloads.ReturnBill r) {
        List<Payloads.Refund> refunds = r.refundTenders() == null ? List.of() : r.refundTenders();
        return new SalesCommands.OfflineReturn(
                creditNoteId,
                r.billId(),
                till.registerId(),
                r.reason(),
                r.lines().stream()
                        .map(l -> new SalesCommands.OfflineReturnLine(l.billLineId(), l.qty(), l.restock()))
                        .toList(),
                refunds.stream()
                        .map(f -> new SalesCommands.OfflineRefund(f.method(), f.amount(), f.reference()))
                        .toList(),
                r.approvedBy(),
                r.returnedAt());
    }
}
