package com.bmp.sales.domain.model;

import static com.bmp.sales.domain.SalesFixtures.AT;
import static com.bmp.sales.domain.SalesFixtures.CUSTOMER;
import static com.bmp.sales.domain.SalesFixtures.ROUNDING;
import static com.bmp.sales.domain.SalesFixtures.TODAY;
import static com.bmp.sales.domain.SalesFixtures.assertViolation;
import static com.bmp.sales.domain.SalesFixtures.card;
import static com.bmp.sales.domain.SalesFixtures.cash;
import static com.bmp.sales.domain.SalesFixtures.completed;
import static com.bmp.sales.domain.SalesFixtures.completion;
import static com.bmp.sales.domain.SalesFixtures.credit;
import static com.bmp.sales.domain.SalesFixtures.draft;
import static com.bmp.sales.domain.SalesFixtures.ids;
import static com.bmp.sales.domain.SalesFixtures.line;
import static com.bmp.sales.domain.SalesFixtures.taxed;
import static com.bmp.sales.domain.SalesFixtures.totals;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.bmp.sales.domain.error.SalesErrorCode;
import com.bmp.shared.id.TenantId;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The bill aggregate: completion (SAL-04, SAL-06, SAL-08), void (SAL-09), returns (SAL-10). */
class BillTest {

    static CreditNote returnOf(Bill bill, List<Bill.ReturnLine> lines, List<RefundTender> refunds) {
        return bill.returnLines(
                UUID.randomUUID(),
                "CN-000001",
                "Cracked",
                lines,
                refunds,
                List.of(),
                TODAY,
                7,
                false,
                null,
                AT,
                ROUNDING,
                ids());
    }

    @Test
    void sal02ADraftIsRepricedThenCompletedAndNeverChangesAgain() {
        Bill bill = draft(null, null, line("10", "55.00", "0"));
        bill.reprice(
                SalesChannel.WHOLESALE,
                CUSTOMER,
                UUID.randomUUID(),
                4,
                List.of(line("300", "50.00", "0"), taxed("2", "1180.00", true)),
                new BillDiscount(DiscountType.AMOUNT, new BigDecimal("100.00"), "Loyal", null),
                ROUNDING);

        assertThat(bill.status()).isEqualTo(BillStatus.DRAFT);
        assertThat(bill.totals().grandTotal()).isEqualByComparingTo("17260.00");
        assertThat(bill.billDiscountAmount()).isEqualByComparingTo("100.00");
        assertThat(bill.channel()).isEqualTo(SalesChannel.WHOLESALE);
        bill.complete(completion(bill, Mode.ONLINE, bill.totals(), credit("17260.00")));
        assertThat(bill.status()).isEqualTo(BillStatus.COMPLETED);
        assertThat(bill.creditAmount()).isEqualByComparingTo("17260.00");
        assertThat(bill.businessDate()).isEqualTo(TODAY);
        assertThat(bill.flags()).isEmpty();
        assertViolation(
                () -> bill.complete(completion(bill, Mode.ONLINE, bill.totals(), cash("1", "1"))),
                SalesErrorCode.BILL_STATE_INVALID);
        assertViolation(
                () -> bill.reprice(SalesChannel.RETAIL, null, null, null, List.of(line("1", "1", "0")), null, ROUNDING),
                SalesErrorCode.BILL_STATE_INVALID);
        assertThatThrownBy(() -> draft(null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sal04OnlineTotalsMustMatchOfflineOnesAreKeptAndFlagged() {
        Bill online = draft(null, null, line("10", "55.00", "0"));
        Totals wrong = totals("550.00", "0", "0", "550.02");
        assertViolation(
                () -> online.complete(completion(online, Mode.ONLINE, wrong, cash("550.02", "600"))),
                SalesErrorCode.TOTALS_MISMATCH);

        Bill offline = draft(null, null, line("10", "55.00", "0"));
        offline.complete(completion(offline, Mode.OFFLINE, wrong, cash("550.02", "600")));
        assertThat(offline.flags()).containsExactly(BillFlag.TOTALS_MISMATCH);
        assertThat(offline.deviceTotals()).isEqualTo(wrong);
        assertThat(offline.totals().grandTotal()).as("the server's own").isEqualByComparingTo("550.00");

        Bill close = draft(null, null, line("10", "55.00", "0"));
        close.complete(completion(close, Mode.OFFLINE, totals("550.00", "0", "0", "550.01"), cash("550.01", "600")));
        assertThat(close.flags()).as("a cent is tolerated").isEmpty();
        assertThat(close.deviceTotals()).isNotNull();
    }

    @Test
    void sal08CompletionKeepsTheTendersFlagsAndShift() {
        Bill bill = draft(null, null, line("10", "55.00", "0"));
        UUID shift = UUID.randomUUID();
        UUID cashier = UUID.randomUUID();
        bill.complete(new Bill.Completion(
                List.of(card("50.00", "APPR 1"), cash("500.00", "1000.00")),
                bill.totals(),
                AT,
                TODAY,
                shift,
                cashier,
                Mode.OFFLINE,
                new com.bmp.sales.domain.service.TenderValidator.Rules(true, null, false),
                Set.of(BillFlag.LATE_SYNC, BillFlag.PRICE_MISMATCH)));

        assertThat(bill.flags()).containsExactlyInAnyOrder(BillFlag.LATE_SYNC, BillFlag.PRICE_MISMATCH);
        assertThat(bill.shiftId()).isEqualTo(shift);
        assertThat(bill.cashierId()).isEqualTo(cashier);
        assertThat(bill.tenders()).extracting(BillTender::method).containsExactly(TenderMethod.CARD, TenderMethod.CASH);
        assertThat(bill.tenders().get(1).changeGiven()).isEqualByComparingTo("500.00");
        assertThat(bill.tenders().get(1).tender()).isInstanceOf(Tender.Cash.class);
        assertThat(bill.creditAmount()).isZero();
    }

    @Test
    void sal09ABillIsVoidedOnItsBusinessDateOnly() {
        Bill bill = completed(cash("2910.00", "3000.00"));
        UUID manager = UUID.randomUUID();
        assertViolation(
                () -> bill.voidBill("Wrong", manager, null, AT, TODAY.plusDays(1)), SalesErrorCode.VOID_WINDOW_PASSED);

        bill.voidBill("Wrong customer", manager, null, AT, TODAY);

        assertThat(bill.status()).isEqualTo(BillStatus.VOIDED);
        assertThat(bill.voidReason()).isEqualTo("Wrong customer");
        assertThat(bill.voidApprovedBy()).isEqualTo(manager);
        assertThat(bill.voidedAt()).isEqualTo(AT);
        assertViolation(() -> bill.voidBill("Again", manager, null, AT, TODAY), SalesErrorCode.BILL_STATE_INVALID);
        assertViolation(
                () -> returnOf(
                        bill,
                        List.of(new Bill.ReturnLine(bill.lines().getFirst().id(), BigDecimal.ONE, true)),
                        List.of()),
                SalesErrorCode.BILL_STATE_INVALID);
    }

    @Test
    void sal10ReturnsArePaidTheirShareAndRefundedByTheOriginalMethod() {
        Bill bill = completed(card("2910.00", "APPR 9"));
        BillLine eggs = bill.lines().get(0);

        CreditNote first =
                returnOf(bill, List.of(new Bill.ReturnLine(eggs.id(), new BigDecimal("2"), true)), List.of());

        assertThat(first.total()).isEqualByComparingTo("110.00");
        assertThat(first.refundTenders()).singleElement().satisfies(r -> {
            assertThat(r.method()).isEqualTo(TenderMethod.CARD);
            assertThat(r.originalTenderId()).isEqualTo(bill.tenders().getFirst().id());
        });
        assertThat(first.lines()).singleElement().satisfies(l -> {
            assertThat(l.restock()).isTrue();
            assertThat(l.qtyBase()).isEqualByComparingTo("2");
            assertThat(l.billLineId()).isEqualTo(eggs.id());
        });
        assertThat(bill.status()).isEqualTo(BillStatus.PARTIALLY_RETURNED);
        assertViolation(
                () -> returnOf(bill, List.of(new Bill.ReturnLine(eggs.id(), new BigDecimal("9"), true)), List.of()),
                SalesErrorCode.RETURN_QTY_EXCEEDED);
        assertViolation(() -> bill.voidBill("Late", null, null, AT, TODAY), SalesErrorCode.BILL_STATE_INVALID);

        CreditNote rest = returnOf(
                bill,
                List.of(
                        new Bill.ReturnLine(eggs.id(), new BigDecimal("8"), false),
                        new Bill.ReturnLine(bill.lines().get(1).id(), new BigDecimal("2"), true)),
                List.of());
        assertThat(rest.total()).isEqualByComparingTo("2800.00");
        assertThat(bill.status()).isEqualTo(BillStatus.RETURNED);
    }

    @Test
    void sal10RefundsGiveBackWhatWasPaidTheWayItWasPaid() {
        Bill paidInCash = completed(cash("2910.00", "3000.00"));
        UUID eggs = paidInCash.lines().getFirst().id();
        assertViolation(
                () -> returnOf(
                        paidInCash,
                        List.of(new Bill.ReturnLine(eggs, new BigDecimal("2"), true)),
                        List.of(new RefundTender(
                                UUID.randomUUID(), TenderMethod.CREDIT, new BigDecimal("110.00"), null, null))),
                SalesErrorCode.REFUND_EXCEEDS_PAID);

        Bill partlyOnAccount = draft(CUSTOMER, null, line("10", "55.00", "0"), line("2", "1180.00", "0"));
        partlyOnAccount.complete(completion(
                partlyOnAccount, Mode.ONLINE, partlyOnAccount.totals(), credit("2000.00"), cash("910.00", "910.00")));
        UUID oil = partlyOnAccount.lines().get(1).id();
        assertViolation(
                () -> returnOf(
                        partlyOnAccount,
                        List.of(new Bill.ReturnLine(oil, new BigDecimal("2"), true)),
                        List.of(new RefundTender(
                                UUID.randomUUID(), TenderMethod.CASH, new BigDecimal("2360.00"), null, null))),
                SalesErrorCode.REFUND_EXCEEDS_PAID);

        CreditNote back =
                returnOf(partlyOnAccount, List.of(new Bill.ReturnLine(oil, new BigDecimal("2"), true)), List.of());

        assertThat(back.refundTenders())
                .as("what is owed on account is reduced first, the rest comes back in cash")
                .extracting(RefundTender::method, r -> r.amount().toPlainString())
                .containsExactly(tuple(TenderMethod.CREDIT, "2000.00"), tuple(TenderMethod.CASH, "360.00"));
    }

    @Test
    void sal10MixedTendersAreRefundedInCashAndTheWindowNeedsAnOverride() {
        Bill bill = completed(card("2000.00", "APPR 9"), cash("910.00", "1000.00"));
        UUID eggs = bill.lines().getFirst().id();
        List<Bill.ReturnLine> two = List.of(new Bill.ReturnLine(eggs, new BigDecimal("2"), true));

        assertViolation(
                () -> bill.returnLines(
                        UUID.randomUUID(),
                        "CN-1",
                        "Late",
                        two,
                        List.of(),
                        List.of(),
                        TODAY.plusDays(8),
                        7,
                        false,
                        null,
                        AT,
                        ROUNDING,
                        ids()),
                SalesErrorCode.RETURN_WINDOW_PASSED);
        assertViolation(
                () -> returnOf(
                        bill,
                        two,
                        List.of(new RefundTender(
                                UUID.randomUUID(), TenderMethod.CASH, new BigDecimal("100.00"), null, null))),
                SalesErrorCode.REFUND_TENDER_MISMATCH);
        assertViolation(
                () -> returnOf(bill, List.of(new Bill.ReturnLine(UUID.randomUUID(), BigDecimal.ONE, true)), List.of()),
                SalesErrorCode.UNKNOWN_BILL_LINE);
        assertViolation(
                () -> returnOf(bill, List.of(new Bill.ReturnLine(eggs, BigDecimal.ZERO, true)), List.of()),
                SalesErrorCode.RETURN_QTY_EXCEEDED);

        CreditNote late = bill.returnLines(
                UUID.randomUUID(),
                "CN-1",
                "Late",
                two,
                List.of(),
                List.of(),
                TODAY.plusDays(8),
                7,
                true,
                UUID.randomUUID(),
                AT,
                ROUNDING,
                ids());
        assertThat(late.refundTenders()).singleElement().satisfies(r -> {
            assertThat(r.method()).isEqualTo(TenderMethod.CASH);
            assertThat(r.originalTenderId()).isNull();
        });
        assertThat(late.approvedBy()).isNotNull();
        assertThat(late.number()).isEqualTo("CN-1");
        assertThat(late.billId()).isEqualTo(bill.id());
    }

    @Test
    void registersRememberTheirLastNumberAndShiftsCoverTheirTime() {
        Register register = Register.create(
                UUID.randomUUID(), TenantId.of(UUID.randomUUID()), "r1", "Front", UUID.randomUUID(), null, "R1");
        register.accepted(42);
        register.accepted(7);
        assertThat(register.lastNumber()).isEqualTo(42);
        assertThat(register.code()).isEqualTo("R1");
        assertThat(register.isActive()).isTrue();
        register.changeStatus("INACTIVE");
        assertThat(register.isActive()).isFalse();

        Shift shift = Shift.open(UUID.randomUUID(), TenantId.of(UUID.randomUUID()), register.id(), null, AT, false);
        assertThat(shift.covers(AT.plusSeconds(60))).isTrue();
        assertThat(shift.covers(AT.minusSeconds(1))).isFalse();
        shift.close(AT.plusSeconds(3600));
        assertThat(shift.isOpen()).isFalse();
        assertThat(shift.covers(AT.plusSeconds(7200))).isFalse();
        assertViolation(() -> shift.close(AT), SalesErrorCode.SHIFT_STATE_INVALID);
    }
}
