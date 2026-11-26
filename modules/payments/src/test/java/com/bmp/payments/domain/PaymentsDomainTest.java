package com.bmp.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.payments.domain.model.CashMovement;
import com.bmp.payments.domain.model.CashMovementType;
import com.bmp.payments.domain.model.DenominationCount;
import com.bmp.payments.domain.model.DrawerSession;
import com.bmp.payments.domain.model.DrawerStatus;
import com.bmp.payments.domain.model.Payment;
import com.bmp.payments.domain.model.PaymentKind;
import com.bmp.payments.domain.model.PaymentMethod;
import com.bmp.payments.domain.model.Refund;
import com.bmp.payments.domain.service.ExpectedCash;
import com.bmp.payments.domain.service.RefundAllocation;
import com.bmp.payments.domain.service.RefundAllocation.Balance;
import com.bmp.shared.error.BmpException;
import com.bmp.shared.id.TenantId;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PaymentsDomainTest {

    private static final Instant NOW = Instant.parse("2026-11-25T09:00:00Z");
    private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
    private static final List<BigDecimal> LKR = List.of("5000", "1000", "500", "100", "10", "1").stream()
            .map(BigDecimal::new)
            .toList();

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    private static String code(Throwable e) {
        return ((BmpException) e).errorCode().code();
    }

    @Nested
    class Tenders {

        @Test
        void pay02CashCoversTheAmountAndGivesChange() {
            assertThat(Payment.checkTender(PaymentMethod.CASH, d("660.00"), d("1000.00"), null, true))
                    .isEqualByComparingTo("340.00");
            assertThat(Payment.checkTender(PaymentMethod.CASH, d("660.00"), null, null, true))
                    .as("exact cash")
                    .isEqualByComparingTo("0");
            assertThatThrownBy(() -> Payment.checkTender(PaymentMethod.CASH, d("660.00"), d("600.00"), null, true))
                    .satisfies(e -> assertThat(code(e)).isEqualTo("TENDERED_TOO_LOW"));
        }

        @Test
        void sal06CardsAndTransfersCarryAReferenceUnlessTheTenantRelaxesIt() {
            assertThat(Payment.checkTender(PaymentMethod.CARD, d("50.00"), null, "AUTH-1", true))
                    .isNull();
            assertThatThrownBy(() -> Payment.checkTender(PaymentMethod.CARD, d("50.00"), null, null, true))
                    .satisfies(e -> assertThat(code(e)).isEqualTo("REFERENCE_REQUIRED"));
            assertThatThrownBy(() -> Payment.checkTender(PaymentMethod.BANK_TRANSFER, d("50.00"), null, " ", true))
                    .satisfies(e -> assertThat(code(e)).isEqualTo("REFERENCE_REQUIRED"));
            assertThat(Payment.checkTender(PaymentMethod.CARD, d("50.00"), null, null, false))
                    .isNull();
            assertThat(Payment.checkTender(PaymentMethod.MOBILE_WALLET, d("50.00"), null, null, true))
                    .as("wallets need no reference")
                    .isNull();
        }

        @Test
        void pay05ACardNumberAnywhereIsRefused() {
            assertThatThrownBy(() ->
                            Payment.checkTender(PaymentMethod.CARD, d("50.00"), null, "4111 1111 1111 1111", true))
                    .satisfies(e -> assertThat(code(e)).isEqualTo("CARD_DATA_NOT_ALLOWED"));
            assertThatThrownBy(() -> Payment.checkReference("refund to 4111-1111-1111-1111"))
                    .satisfies(e -> assertThat(code(e)).isEqualTo("CARD_DATA_NOT_ALLOWED"));
            Payment.checkReference("AUTH-4471");
            Payment.checkReference(null);
        }

        @Test
        void methodsOfTendersAreMoneyTakenExceptCredit() {
            assertThat(PaymentMethod.ofTender("CASH")).contains(PaymentMethod.CASH);
            assertThat(PaymentMethod.ofTender("CHEQUE")).contains(PaymentMethod.CHEQUE);
            assertThat(PaymentMethod.ofTender("CREDIT")).isEmpty();
            assertThat(PaymentMethod.CARD.needsReference()).isTrue();
            assertThat(PaymentMethod.BANK_TRANSFER.needsReference()).isTrue();
            assertThat(PaymentMethod.CASH.needsReference()).isFalse();
            assertThat(PaymentMethod.CHEQUE.needsReference()).isFalse();
        }

        @Test
        void aPaymentTakesMoneyAndABillPaymentNamesItsBillAndTender() {
            assertThat(payment(PaymentKind.BILL, UUID.randomUUID(), UUID.randomUUID(), "10.00")
                            .amount())
                    .isEqualByComparingTo("10.00");
            assertThat(payment(PaymentKind.ACCOUNT, null, null, "10.00").kind()).isEqualTo(PaymentKind.ACCOUNT);
            assertThatThrownBy(() -> payment(PaymentKind.ACCOUNT, null, null, "0"))
                    .satisfies(e -> assertThat(code(e)).isEqualTo("VALIDATION_FAILED"));
            assertThatThrownBy(() -> payment(PaymentKind.BILL, UUID.randomUUID(), null, "10.00"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> payment(PaymentKind.BILL, null, UUID.randomUUID(), "10.00"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Refund(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            BigDecimal.ZERO,
                            PaymentMethod.CASH,
                            "LKR",
                            "Test",
                            null,
                            null,
                            null,
                            null,
                            LocalDate.of(2026, 11, 25),
                            NOW))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        private Payment payment(PaymentKind kind, UUID bill, UUID tender, String amount) {
            return new Payment(
                    UUID.randomUUID(),
                    kind,
                    bill,
                    tender,
                    null,
                    PaymentMethod.CASH,
                    d(amount),
                    "LKR",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    LocalDate.of(2026, 11, 25),
                    NOW);
        }
    }

    @Nested
    class Drawers {

        private final UUID cashier = UUID.randomUUID();

        private DrawerSession open() {
            return DrawerSession.open(UUID.randomUUID(), TENANT, UUID.randomUUID(), cashier, d("5000.00"), "LKR", NOW);
        }

        private static CashMovement movement(CashMovementType type, String amount) {
            return new CashMovement(UUID.randomUUID(), UUID.randomUUID(), type, d(amount), null, "MANUAL", null, NOW);
        }

        @Test
        void pay04ExpectedIsFloatPlusSalesLessRefundsLessPaidOutPlusPaidInLessDrops() {
            ExpectedCash expected = ExpectedCash.of(List.of(
                    movement(CashMovementType.FLOAT, "5000.00"),
                    movement(CashMovementType.SALE, "660.00"),
                    movement(CashMovementType.SALE, "60.00"),
                    movement(CashMovementType.REFUND, "110.00"),
                    movement(CashMovementType.PAID_OUT, "250.00"),
                    movement(CashMovementType.PAID_IN, "1000.00"),
                    movement(CashMovementType.DROP, "2000.00")));

            assertThat(expected.openingFloat()).isEqualByComparingTo("5000.00");
            assertThat(expected.sales()).isEqualByComparingTo("720.00");
            assertThat(expected.refunds()).isEqualByComparingTo("110.00");
            assertThat(expected.paidOut()).isEqualByComparingTo("250.00");
            assertThat(expected.paidIn()).isEqualByComparingTo("1000.00");
            assertThat(expected.drops()).isEqualByComparingTo("2000.00");
            assertThat(expected.expected()).isEqualByComparingTo("4360.00");
            assertThat(ExpectedCash.of(List.of()).expected()).isEqualByComparingTo("0");
        }

        @Test
        void cashMovementsArePositiveAndTheirTypeGivesTheDirection() {
            assertThat(movement(CashMovementType.SALE, "10.00").signed()).isEqualByComparingTo("10.00");
            assertThat(movement(CashMovementType.DROP, "10.00").signed()).isEqualByComparingTo("-10.00");
            assertThatThrownBy(() -> movement(CashMovementType.PAID_IN, "-1"))
                    .satisfies(e -> assertThat(code(e)).isEqualTo("VALIDATION_FAILED"));
            assertThat(Set.of(CashMovementType.values()).stream().filter(CashMovementType::manual))
                    .containsExactlyInAnyOrder(
                            CashMovementType.PAID_OUT, CashMovementType.PAID_IN, CashMovementType.DROP);
            assertThat(CashMovementType.FLOAT.into()).isTrue();
            assertThat(CashMovementType.REFUND.into()).isFalse();
        }

        @Test
        void pay04ACountByDenominationGivesTheVarianceAndCanBeRepeatedUntilReconciled() {
            DrawerSession drawer = open();
            assertThat(drawer.status()).isEqualTo(DrawerStatus.OPEN);
            assertThat(drawer.takesCash()).isTrue();
            assertThat(drawer.openedBy(cashier)).isTrue();
            assertThat(drawer.openedBy(UUID.randomUUID())).isFalse();
            assertThat(drawer.openedBy(null)).isFalse();

            drawer.count(
                    List.of(
                            new DenominationCount(d("5000"), 1),
                            new DenominationCount(d("100.00"), 5),
                            new DenominationCount(d("100"), 1),
                            new DenominationCount(d("10"), 0)),
                    LKR,
                    d("5700.00"),
                    cashier,
                    NOW);

            assertThat(drawer.status()).isEqualTo(DrawerStatus.COUNTED);
            assertThat(drawer.countedCash()).isEqualByComparingTo("5600");
            assertThat(drawer.variance()).as("100.00 short").isEqualByComparingTo("-100.00");
            assertThat(drawer.denominations())
                    .as("the same value twice adds up, largest first")
                    .extracting(c -> c.value().toPlainString() + "x" + c.count())
                    .containsExactly("5000x1", "100x6", "10x0");
            assertThat(drawer.takesCash()).as("sales land on a counted drawer").isTrue();
            assertThatThrownBy(drawer::requireOpen)
                    .satisfies(e -> assertThat(code(e)).isEqualTo("DRAWER_STATE_INVALID"));

            drawer.count(
                    List.of(new DenominationCount(d("5000"), 1), new DenominationCount(d("1000"), 1)),
                    LKR,
                    d("5700.00"),
                    cashier,
                    NOW);
            assertThat(drawer.countedCash()).isEqualByComparingTo("6000");
            assertThat(drawer.variance()).isEqualByComparingTo("300.00");

            drawer.reconcile(d("5710.00"), UUID.randomUUID(), "Late sale", NOW.plusSeconds(60));
            assertThat(drawer.status()).isEqualTo(DrawerStatus.RECONCILED);
            assertThat(drawer.variance())
                    .as("against the expected cash at reconciliation")
                    .isEqualByComparingTo("290.00");
            assertThat(drawer.expectedCash()).isEqualByComparingTo("5710.00");
            assertThat(drawer.reconciledAt()).isEqualTo(NOW.plusSeconds(60));
            assertThat(drawer.takesCash()).isFalse();
            assertThatThrownBy(() -> drawer.count(List.of(), LKR, BigDecimal.ZERO, cashier, NOW))
                    .satisfies(e -> assertThat(code(e)).isEqualTo("DRAWER_STATE_INVALID"));
            assertThatThrownBy(() -> drawer.reconcile(BigDecimal.ZERO, null, null, NOW))
                    .satisfies(e -> assertThat(code(e)).isEqualTo("DRAWER_STATE_INVALID"));
        }

        @Test
        void onlyTheTenantsDenominationsCountAndNothingIsNegative() {
            DrawerSession drawer = open();
            assertThatThrownBy(() -> drawer.count(List.of(new DenominationCount(d("20"), 1)), LKR, d("0"), null, NOW))
                    .satisfies(e -> assertThat(code(e)).isEqualTo("DENOMINATION_UNKNOWN"));
            assertThatThrownBy(() -> drawer.count(List.of(new DenominationCount(d("10"), -1)), LKR, d("0"), null, NOW))
                    .satisfies(e -> assertThat(code(e)).isEqualTo("VALIDATION_FAILED"));
            assertThatThrownBy(() -> drawer.reconcile(d("0"), null, null, NOW))
                    .as("reconciling needs a count")
                    .satisfies(e -> assertThat(code(e)).isEqualTo("DRAWER_STATE_INVALID"));
            assertThatThrownBy(() ->
                            DrawerSession.open(UUID.randomUUID(), TENANT, UUID.randomUUID(), null, d("-1"), "LKR", NOW))
                    .satisfies(e -> assertThat(code(e)).isEqualTo("VALIDATION_FAILED"));
            drawer.requireOpen();
            assertThat(drawer.openingFloat()).isEqualByComparingTo("5000.00");
            assertThat(drawer.currency()).isEqualTo("LKR");
            assertThat(drawer.version()).isZero();
        }
    }

    @Nested
    class Refunds {

        private final UUID cash = UUID.randomUUID();
        private final UUID card = UUID.randomUUID();

        private List<Balance> bill(String cashRefunded) {
            return List.of(
                    new Balance(cash, PaymentMethod.CASH, d("60.00"), d(cashRefunded)),
                    new Balance(card, PaymentMethod.CARD, d("50.00"), BigDecimal.ZERO));
        }

        @Test
        void pay03ARefundComesFromPaymentsOfItsMethodFirst() {
            RefundAllocation.Plan plan = RefundAllocation.allocate(d("50.00"), PaymentMethod.CARD, bill("0"));

            assertThat(plan.parts()).singleElement().satisfies(p -> {
                assertThat(p.paymentId()).isEqualTo(card);
                assertThat(p.amount()).isEqualByComparingTo("50.00");
            });
            assertThat(plan.unallocated()).isZero();
        }

        @Test
        void pay03ACashRefundOfAMixedBillSpillsOverButNeverBeyondAPayment() {
            RefundAllocation.Plan plan = RefundAllocation.allocate(d("110.00"), PaymentMethod.CASH, bill("20.00"));

            assertThat(plan.parts()).hasSize(2);
            assertThat(plan.parts().get(0).paymentId()).isEqualTo(cash);
            assertThat(plan.parts().get(0).amount()).as("60 less 20 refunded").isEqualByComparingTo("40.00");
            assertThat(plan.parts().get(1).paymentId()).isEqualTo(card);
            assertThat(plan.parts().get(1).amount()).isEqualByComparingTo("50.00");
            assertThat(plan.unallocated()).isEqualByComparingTo("20.00");
        }

        @Test
        void aFullyRefundedPaymentGivesNothingMore() {
            RefundAllocation.Plan plan = RefundAllocation.allocate(
                    d("10.00"),
                    PaymentMethod.CASH,
                    List.of(new Balance(cash, PaymentMethod.CASH, d("60.00"), d("70.00"))));

            assertThat(plan.parts()).isEmpty();
            assertThat(plan.unallocated()).isEqualByComparingTo("10.00");
            assertThat(new Balance(cash, PaymentMethod.CASH, d("60.00"), d("70.00")).remaining())
                    .isZero();
        }
    }
}
