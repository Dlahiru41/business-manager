package com.bmp.procurement.domain.model;

import static com.bmp.procurement.domain.model.GoodsReceiptTest.assertViolation;
import static com.bmp.procurement.domain.model.ProcurementFixtures.AT;
import static com.bmp.procurement.domain.model.ProcurementFixtures.IDS;
import static com.bmp.procurement.domain.model.ProcurementFixtures.LOCATION;
import static com.bmp.procurement.domain.model.ProcurementFixtures.ROUNDING;
import static com.bmp.procurement.domain.model.ProcurementFixtures.SUPPLIER;
import static com.bmp.procurement.domain.model.ProcurementFixtures.TENANT;
import static com.bmp.procurement.domain.model.ProcurementFixtures.piece;
import static com.bmp.procurement.domain.model.ProcurementFixtures.tray;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.procurement.domain.error.ProcurementErrorCode;
import com.bmp.shared.error.PlatformErrorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PurchaseOrderTest {

    static final UUID EGG = UUID.randomUUID();
    static final UUID OIL = UUID.randomUUID();

    static PurchaseOrder order(List<PurchaseOrder.Line> lines) {
        return PurchaseOrder.draft(
                UUID.randomUUID(), TENANT, "PO-000001", SUPPLIER, LOCATION, "LKR", null, null, lines, IDS);
    }

    static PurchaseOrder.Line line(Purchasable unit, String qty, String cost) {
        return new PurchaseOrder.Line(unit, new BigDecimal(qty), new BigDecimal(cost));
    }

    @Test
    void pro01ADraftIsChangedThenSent() {
        PurchaseOrder order = order(List.of(line(tray(EGG), "10", "1275.00")));
        order.changeExpectedOn(LocalDate.parse("2026-11-25"));
        order.changeNotes("Morning delivery");
        order.replaceLines(List.of(line(tray(EGG), "12", "1275.00"), line(piece(OIL), "3", "13800.00")), IDS);

        assertThat(order.status()).isEqualTo(OrderStatus.DRAFT);
        assertThat(order.total(ROUNDING)).isEqualByComparingTo("56700.00");
        assertThat(order.isOpen()).isFalse();
        order.send(AT);
        assertThat(order.status()).isEqualTo(OrderStatus.SENT);
        assertThat(order.sentAt()).isEqualTo(AT);
        assertThat(order.isOpen()).isTrue();
        assertThat(order.expectedOn()).isEqualTo(LocalDate.parse("2026-11-25"));
        assertThat(order.notes()).isEqualTo("Morning delivery");
        assertViolation(() -> order.send(AT), ProcurementErrorCode.PURCHASE_ORDER_STATE_INVALID);
        assertViolation(() -> order.changeNotes("x"), ProcurementErrorCode.PURCHASE_ORDER_STATE_INVALID);
        assertViolation(
                () -> order.changeExpectedOn(LocalDate.parse("2026-11-26")),
                ProcurementErrorCode.PURCHASE_ORDER_STATE_INVALID);
        assertViolation(() -> order.replaceLines(List.of(), IDS), ProcurementErrorCode.PURCHASE_ORDER_STATE_INVALID);
    }

    @Test
    void pro01ReceiptsMoveTheOrderToPartlyAndFullyReceived() {
        PurchaseOrder order = order(List.of(line(tray(EGG), "10", "1275.00"), line(piece(OIL), "2", "13800.00")));
        assertViolation(() -> order.received(Map.of()), ProcurementErrorCode.PURCHASE_ORDER_STATE_INVALID);
        order.send(AT);
        UUID trays = order.lines().get(0).id();
        UUID cans = order.lines().get(1).id();

        order.received(Map.of(trays, new BigDecimal("10")));
        assertThat(order.status()).isEqualTo(OrderStatus.PARTIALLY_RECEIVED);
        assertViolation(() -> order.cancel(AT), ProcurementErrorCode.PURCHASE_ORDER_STATE_INVALID);
        order.received(Map.of(cans, new BigDecimal("1")));
        assertThat(order.status()).isEqualTo(OrderStatus.PARTIALLY_RECEIVED);
        order.received(Map.of(cans, new BigDecimal("1.5")));
        assertThat(order.status())
                .as("more than ordered still closes the order")
                .isEqualTo(OrderStatus.RECEIVED);
        assertThat(order.line(cans).orElseThrow().receivedQty()).isEqualByComparingTo("2.5");
        assertThat(order.isOpen()).isFalse();
        assertViolation(
                () -> order.received(Map.of(cans, BigDecimal.ONE)), ProcurementErrorCode.PURCHASE_ORDER_STATE_INVALID);
    }

    @Test
    void aReceiptForAnotherOrdersLineIsAMismatch() {
        PurchaseOrder order = order(List.of(line(tray(EGG), "10", "1275.00")));
        order.send(AT);

        assertViolation(
                () -> order.received(Map.of(UUID.randomUUID(), BigDecimal.ONE)),
                ProcurementErrorCode.PURCHASE_ORDER_MISMATCH);
        assertThat(order.line(UUID.randomUUID())).isEmpty();
    }

    @Test
    void anOrderIsCancelledOnlyBeforeAnythingArrives() {
        PurchaseOrder draft = order(List.of(line(tray(EGG), "10", "1275.00")));
        draft.cancel(AT);
        assertThat(draft.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(draft.cancelledAt()).isEqualTo(AT);
        assertViolation(() -> draft.cancel(AT), ProcurementErrorCode.PURCHASE_ORDER_STATE_INVALID);

        PurchaseOrder sent = order(List.of(line(tray(EGG), "1", "1275.00")));
        sent.send(AT);
        sent.cancel(AT);
        assertThat(sent.status()).isEqualTo(OrderStatus.CANCELLED);

        PurchaseOrder received = order(List.of(line(tray(EGG), "1", "1275.00")));
        received.send(AT);
        received.received(Map.of(received.lines().getFirst().id(), BigDecimal.ONE));
        assertViolation(() -> received.cancel(AT), ProcurementErrorCode.PURCHASE_ORDER_STATE_INVALID);
    }

    @Test
    void orderedQuantitiesArePositiveAndCostsNotNegative() {
        assertViolation(() -> order(List.of(line(tray(EGG), "0", "1275.00"))), PlatformErrorCode.VALIDATION_FAILED);
        assertViolation(() -> order(List.of(line(tray(EGG), "-1", "1275.00"))), PlatformErrorCode.VALIDATION_FAILED);
        assertViolation(() -> order(List.of(line(tray(EGG), "1", "-0.01"))), PlatformErrorCode.VALIDATION_FAILED);
        assertThat(order(List.of(line(tray(EGG), "1", "0"))).total(ROUNDING))
                .as("a free sample")
                .isZero();
    }
}
