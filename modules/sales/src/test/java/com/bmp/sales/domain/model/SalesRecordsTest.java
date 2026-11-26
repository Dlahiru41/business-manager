package com.bmp.sales.domain.model;

import static com.bmp.sales.domain.SalesFixtures.AT;
import static com.bmp.sales.domain.SalesFixtures.ROUNDING;
import static com.bmp.sales.domain.SalesFixtures.TODAY;
import static com.bmp.sales.domain.SalesFixtures.card;
import static com.bmp.sales.domain.SalesFixtures.completed;
import static com.bmp.sales.domain.SalesFixtures.ids;
import static com.bmp.sales.domain.SalesFixtures.totals;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What the sales records keep: audit stamps, register settings, shift times, tender kinds (SAL-01, SAL-06, SAL-12). */
class SalesRecordsTest {

    static final UserId USER = UserId.of(UUID.randomUUID());

    @Test
    void sal06OnlyCardsAndTransfersNeedAReference() {
        assertThat(List.of(TenderMethod.values()))
                .filteredOn(TenderMethod::needsReference)
                .containsExactly(TenderMethod.CARD, TenderMethod.BANK_TRANSFER);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Tender.NonCash(UUID.randomUUID(), TenderMethod.CASH, BigDecimal.ONE, null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Tender.NonCash(UUID.randomUUID(), TenderMethod.CREDIT, BigDecimal.ONE, null));
        assertThat(Tender.of(UUID.randomUUID(), TenderMethod.CASH, BigDecimal.TEN, null, null))
                .isInstanceOfSatisfying(
                        Tender.Cash.class, c -> assertThat(c.change()).isZero());
        assertThat(Tender.of(UUID.randomUUID(), TenderMethod.CHEQUE, BigDecimal.TEN, null, "CHQ 1")
                        .reference())
                .isEqualTo("CHQ 1");
        assertThat(Mode.OFFLINE.online()).isFalse();
    }

    @Test
    void sal04TotalsDifferWhenAnyFigureIsBeyondTheTolerance() {
        Totals base = totals("100.00", "10.00", "5.00", "95.00");
        BigDecimal cent = new BigDecimal("0.01");
        assertThat(base.differsFrom(totals("100.02", "10.00", "5.00", "95.00"), cent))
                .isTrue();
        assertThat(base.differsFrom(totals("100.00", "10.02", "5.00", "95.00"), cent))
                .isTrue();
        assertThat(base.differsFrom(totals("100.00", "10.00", "5.02", "95.00"), cent))
                .isTrue();
        assertThat(base.differsFrom(totals("100.01", "10.01", "5.01", "95.01"), cent))
                .isFalse();
        assertThat(Totals.ZERO.grandTotal()).isZero();
    }

    @Test
    void sal01RegistersKeepTheirSettingsAndStamps() {
        UUID device = UUID.randomUUID();
        Register register = Register.create(
                UUID.randomUUID(), TenantId.of(UUID.randomUUID()), "r2", null, UUID.randomUUID(), null, "R2");
        register.rename("Back counter");
        register.bindDevice(device);
        register.changePrefix("B2");
        register.recordCreation(AT, USER);
        register.recordModification(AT.plusSeconds(1), null);

        assertThat(register.name()).isEqualTo("Back counter");
        assertThat(register.deviceId()).isEqualTo(device);
        assertThat(register.billPrefix()).isEqualTo("B2");
        assertThat(register.status()).isEqualTo("ACTIVE");
        assertThat(register.locationId()).isNotNull();
        assertThat(register.version()).isZero();
        assertThat(register.audit().createdAt()).isEqualTo(AT);
        assertThat(register.audit().updatedAt()).isEqualTo(AT.plusSeconds(1));
    }

    @Test
    void sal12ShiftsKnowTheirCashierAndTimes() {
        UUID cashier = UUID.randomUUID();
        Shift shift =
                Shift.open(UUID.randomUUID(), TenantId.of(UUID.randomUUID()), UUID.randomUUID(), cashier, AT, true);
        shift.close(AT.minusSeconds(5));

        assertThat(shift.closedAt()).as("never before it opened").isEqualTo(AT);
        assertThat(shift.cashierId()).isEqualTo(cashier);
        assertThat(shift.offline()).isTrue();
        assertThat(shift.status()).isEqualTo("CLOSED");
        assertThat(shift.openedAt()).isEqualTo(AT);
        assertThat(shift.registerId()).isNotNull();
        assertThat(shift.version()).isZero();
        assertThat(shift.covers(AT)).isTrue();
    }

    @Test
    void sal08BillsAndCreditNotesKeepWhatWasSoldAndReturned() {
        Bill bill = completed(card("2910.00", "APPR 1"));
        bill.recordCreation(AT, USER);
        bill.recordModification(AT, USER);
        BillLine line = bill.lines().getFirst();
        CreditNote note = bill.returnLines(
                UUID.randomUUID(),
                "CN-000001",
                "Cracked",
                List.of(new Bill.ReturnLine(line.id(), BigDecimal.ONE, false)),
                List.of(),
                List.of(),
                TODAY,
                7,
                false,
                null,
                AT,
                ROUNDING,
                ids());
        note.recordCreation(AT, USER);
        note.recordModification(AT, null);

        assertThat(bill.number()).isEqualTo("R1-000042");
        assertThat(bill.registerId()).isNotNull();
        assertThat(bill.locationId()).isNotNull();
        assertThat(bill.deviceId()).isNull();
        assertThat(bill.priceListId()).isNull();
        assertThat(bill.priceListVersion()).isEqualTo(3);
        assertThat(bill.discount()).isNull();
        assertThat(bill.currency()).isEqualTo("LKR");
        assertThat(bill.completedAt()).isEqualTo(AT);
        assertThat(bill.offline()).isFalse();
        assertThat(bill.version()).isZero();
        assertThat(bill.audit().createdAt()).isEqualTo(AT);
        assertThat(line.variantId()).isNotNull();
        assertThat(line.packagingUnitId()).isNotNull();
        assertThat(line.description()).isEqualTo("Eggs Red L");
        assertThat(line.stockState()).isEqualTo("DEFAULT");
        assertThat(line.weighed()).isFalse();
        assertThat(line.unitPrice()).isEqualByComparingTo("55.00");
        assertThat(line.lineDiscount()).isZero();
        assertThat(line.allocatedBillDiscount()).isZero();
        assertThat(line.priceSource()).isEqualTo("LIST");
        assertThat(line.returnedQty()).isEqualByComparingTo("1");
        assertThat(bill.tenders().getFirst().tendered()).isNull();
        assertThat(bill.tenders().getFirst().reference()).isEqualTo("APPR 1");
        assertThat(bill.tenders().getFirst().amount()).isEqualByComparingTo("2910.00");
        assertThat(note.id()).isNotNull();
        assertThat(note.reason()).isEqualTo("Cracked");
        assertThat(note.taxTotal()).isZero();
        assertThat(note.returnedAt()).isEqualTo(AT);
        assertThat(note.audit().createdAt()).isEqualTo(AT);
        assertThat(note.lines().getFirst().id()).isNotNull();
        assertThat(note.lines().getFirst().variantId()).isEqualTo(line.variantId());
        assertThat(note.lines().getFirst().stockState()).isEqualTo("DEFAULT");
        assertThat(note.lines().getFirst().qty()).isEqualByComparingTo("1");
        assertThat(note.lines().getFirst().amount()).isEqualByComparingTo("55.00");
        assertThat(note.lines().getFirst().taxAmount()).isZero();
        assertThat(new AuditStamps().createdAt()).isEqualTo(Instant.EPOCH);
        assertThat(new AuditStamps().updatedAt()).isEqualTo(Instant.EPOCH);
    }
}
