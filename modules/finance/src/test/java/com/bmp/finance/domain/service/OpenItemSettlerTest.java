package com.bmp.finance.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.finance.domain.error.FinanceErrorCode;
import com.bmp.finance.domain.model.DocumentType;
import com.bmp.finance.domain.model.OpenItem;
import com.bmp.finance.domain.model.PartyRef;
import com.bmp.finance.domain.model.Settlement;
import com.bmp.finance.domain.service.OpenItemSettler.Allocation;
import com.bmp.shared.error.BusinessRuleViolation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OpenItemSettlerTest {

    private static final PartyRef CUSTOMER = PartyRef.customer(UUID.randomUUID());

    private static OpenItem bill(String amount, String due) {
        return item(DocumentType.BILL, amount, due);
    }

    private static OpenItem item(DocumentType type, String amount, String due) {
        LocalDate date = LocalDate.parse(due).minusDays(30);
        return OpenItem.open(
                UUID.randomUUID(),
                CUSTOMER,
                type,
                UUID.randomUUID(),
                new BigDecimal(amount),
                date,
                LocalDate.parse(due));
    }

    @Test
    void fin07ACollectionSettlesTheOldestDueBillFirst() {
        OpenItem older = bill("500.00", "2026-11-10");
        OpenItem newer = bill("800.00", "2026-11-25");
        OpenItem collection = item(DocumentType.COLLECTION, "-600.00", "2026-11-28");

        List<Settlement> settled = OpenItemSettler.settle(collection, List.of(newer, older), List.of());

        assertThat(settled)
                .containsExactly(
                        new Settlement(collection.id(), older.id(), new BigDecimal("500.00")),
                        new Settlement(collection.id(), newer.id(), new BigDecimal("100.00")));
        assertThat(older.isOpen()).isFalse();
        assertThat(newer.remaining()).isEqualByComparingTo("700.00");
        assertThat(collection.isOpen()).isFalse();
        assertThat(collection.settled()).isEqualByComparingTo("-600.00");
    }

    @Test
    void fin07AnAllocatedCollectionSettlesTheBillsItNamesFirst() {
        OpenItem older = bill("500.00", "2026-11-10");
        OpenItem newer = bill("800.00", "2026-11-25");
        OpenItem collection = item(DocumentType.COLLECTION, "-1000.00", "2026-11-28");

        List<Settlement> settled = OpenItemSettler.settle(
                collection,
                List.of(older, newer),
                List.of(new Allocation(newer.documentId(), new BigDecimal("800.00"))));

        assertThat(settled).extracting(Settlement::againstItemId).containsExactly(newer.id(), older.id());
        assertThat(newer.isOpen()).isFalse();
        assertThat(older.remaining()).isEqualByComparingTo("300.00");
    }

    @Test
    void fin07AnAllocationNeverTakesMoreThanItsAmountOrTheBillHasOpen() {
        OpenItem small = bill("100.00", "2026-11-10");
        OpenItem other = bill("400.00", "2026-11-12");
        OpenItem collection = item(DocumentType.COLLECTION, "-450.00", "2026-11-28");

        OpenItemSettler.settle(
                collection,
                List.of(small, other),
                List.of(
                        new Allocation(other.documentId(), new BigDecimal("50.00")),
                        new Allocation(small.documentId(), new BigDecimal("999.00")),
                        new Allocation(UUID.randomUUID(), new BigDecimal("10.00"))));

        assertThat(small.isOpen()).isFalse();
        assertThat(other.remaining()).isEqualByComparingTo("50.00");
        assertThat(collection.remaining()).isEqualByComparingTo("-0.00");
    }

    @Test
    void fin07WhatAPaymentCannotSettleStaysOpenAsACreditForTheNextBill() {
        OpenItem bill = bill("300.00", "2026-11-10");
        OpenItem collection = item(DocumentType.COLLECTION, "-500.00", "2026-11-28");
        OpenItemSettler.settle(collection, List.of(bill), List.of());
        assertThat(collection.remaining()).isEqualByComparingTo("-200.00");
        assertThat(collection.isDebit()).isFalse();

        OpenItem next = bill("350.00", "2026-12-30");
        List<Settlement> applied = OpenItemSettler.settle(next, List.of(bill, collection), List.of());

        assertThat(applied).containsExactly(new Settlement(next.id(), collection.id(), new BigDecimal("200.00")));
        assertThat(next.remaining()).isEqualByComparingTo("150.00");
        assertThat(collection.isOpen()).isFalse();
    }

    @Test
    void itemsOfTheSameSideAndSettledItemsAreLeftAlone() {
        OpenItem settledBill = bill("100.00", "2026-10-01");
        settledBill.settle(new BigDecimal("100.00"));
        OpenItem otherCredit = item(DocumentType.COLLECTION, "-50.00", "2026-10-02");
        OpenItem collection = item(DocumentType.COLLECTION, "-60.00", "2026-11-28");

        assertThat(OpenItemSettler.settle(collection, List.of(settledBill, otherCredit, collection), List.of()))
                .isEmpty();
        assertThat(collection.remaining()).isEqualByComparingTo("-60.00");
    }

    @Test
    void tiesOnTheDueDateGoByDocumentDateThenId() {
        LocalDate due = LocalDate.parse("2026-11-30");
        OpenItem later = new OpenItem(
                UUID.randomUUID(),
                CUSTOMER,
                DocumentType.BILL,
                UUID.randomUUID(),
                new BigDecimal("10"),
                BigDecimal.ZERO,
                LocalDate.parse("2026-11-02"),
                due);
        OpenItem earlier = new OpenItem(
                UUID.randomUUID(),
                CUSTOMER,
                DocumentType.BILL,
                UUID.randomUUID(),
                new BigDecimal("10"),
                BigDecimal.ZERO,
                LocalDate.parse("2026-11-01"),
                due);
        OpenItem collection = item(DocumentType.COLLECTION, "-10", "2026-12-01");

        OpenItemSettler.settle(collection, List.of(later, earlier), List.of());

        assertThat(earlier.isOpen()).isFalse();
        assertThat(later.isOpen()).isTrue();
    }

    @Test
    void fin11ApiAllocationsMustNameOpenDocumentsAndFitThePayment() {
        OpenItem invoice = bill("48500.00", "2026-12-26");
        List<OpenItem> open = List.of(invoice);

        assertThat(code(() -> OpenItemSettler.validate(
                        BigDecimal.TEN, false, open, List.of(new Allocation(UUID.randomUUID(), BigDecimal.ONE)))))
                .isEqualTo(FinanceErrorCode.UNKNOWN_OPEN_ITEM);
        assertThat(code(() -> OpenItemSettler.validate(
                        new BigDecimal("50000"),
                        false,
                        open,
                        List.of(new Allocation(invoice.documentId(), new BigDecimal("48500.01"))))))
                .isEqualTo(FinanceErrorCode.ALLOCATION_EXCEEDS_OPEN);
        assertThat(code(() -> OpenItemSettler.validate(
                        new BigDecimal("40000"),
                        false,
                        open,
                        List.of(new Allocation(invoice.documentId(), new BigDecimal("48500.00"))))))
                .isEqualTo(FinanceErrorCode.ALLOCATION_EXCEEDS_PAYMENT);
        assertThat(code(() -> OpenItemSettler.validate(
                        BigDecimal.TEN, true, open, List.of(new Allocation(invoice.documentId(), BigDecimal.ONE)))))
                .isEqualTo(FinanceErrorCode.UNKNOWN_OPEN_ITEM);

        OpenItemSettler.validate(
                new BigDecimal("48500.00"),
                false,
                open,
                List.of(new Allocation(invoice.documentId(), new BigDecimal("48500.00"))));
    }

    @Test
    void anOpenItemKnowsWhatRemainsAndHowLateItIs() {
        OpenItem bill = bill("500.00", "2026-11-10");

        assertThat(bill.daysOverdue(LocalDate.parse("2026-11-20"))).isEqualTo(10);
        assertThat(bill.daysOverdue(LocalDate.parse("2026-11-05"))).isEqualTo(-5);
        assertThatThrownBy(() -> bill.settle(new BigDecimal("500.01"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bill.settle(BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpenItem.open(
                        UUID.randomUUID(),
                        CUSTOMER,
                        DocumentType.BILL,
                        UUID.randomUUID(),
                        BigDecimal.ZERO,
                        LocalDate.EPOCH,
                        LocalDate.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
        bill.settle(new BigDecimal("500.00"));
        assertThat(bill.isOpen()).isFalse();
        assertThat(bill.party()).isEqualTo(CUSTOMER);
        assertThat(bill.documentType()).isEqualTo(DocumentType.BILL);
        assertThat(bill.amount()).isEqualByComparingTo("500.00");
    }

    private static com.bmp.shared.error.ErrorCode code(Runnable action) {
        try {
            action.run();
        } catch (BusinessRuleViolation e) {
            return e.errorCode();
        }
        throw new AssertionError("expected a refusal");
    }
}
