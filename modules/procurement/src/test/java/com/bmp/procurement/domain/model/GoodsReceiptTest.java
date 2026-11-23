package com.bmp.procurement.domain.model;

import static com.bmp.procurement.domain.model.ProcurementFixtures.AT;
import static com.bmp.procurement.domain.model.ProcurementFixtures.IDS;
import static com.bmp.procurement.domain.model.ProcurementFixtures.ROUNDING;
import static com.bmp.procurement.domain.model.ProcurementFixtures.line;
import static com.bmp.procurement.domain.model.ProcurementFixtures.piece;
import static com.bmp.procurement.domain.model.ProcurementFixtures.posted;
import static com.bmp.procurement.domain.model.ProcurementFixtures.receipt;
import static com.bmp.procurement.domain.model.ProcurementFixtures.tray;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.procurement.domain.error.ProcurementErrorCode;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.ErrorCode;
import com.bmp.shared.error.PlatformErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class GoodsReceiptTest {

    static final UUID SMALL = UUID.randomUUID();
    static final UUID MEDIUM = UUID.randomUUID();
    static final UUID LARGE = UUID.randomUUID();

    static void assertViolation(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        BusinessRuleViolation.class,
                        e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    @Test
    void pro03TheEggDeliveryKeepsBaseQuantitiesAndCostsPerPiece() {
        GoodsReceipt receipt = receipt(
                null,
                List.of(
                        line(piece(SMALL), "300", "38.00"),
                        line(piece(MEDIUM), "300", "42.00"),
                        line(piece(LARGE), "260", "46.50")));

        assertThat(receipt.lines())
                .extracting(l -> l.qtyBase().toPlainString())
                .containsExactly("300.000000", "300.000000", "260.000000");
        assertThat(receipt.lines())
                .extracting(l -> l.lineTotal().toPlainString())
                .containsExactly("11400.00", "12600.00", "12090.00");
        assertThat(receipt.total()).isEqualByComparingTo("36090.00");
        assertThat(receipt.status()).isEqualTo(ReceiptStatus.DRAFT);
        assertThat(receipt.isReturn()).isFalse();
    }

    @Test
    void pro03TraysAreConvertedToPiecesAtScaleSix() {
        GoodsReceipt receipt = receipt(null, List.of(line(tray(MEDIUM), "10", "1275.00")));
        ReceiptLine line = receipt.lines().getFirst();

        assertThat(line.qtyBase()).isEqualByComparingTo("300");
        assertThat(line.unitCostBase().toPlainString()).isEqualTo("42.500000");
        assertThat(line.lineTotal().toPlainString()).isEqualTo("12750.00");
        assertThat(receipt(null, List.of(line(tray(SMALL), "1", "1000.00")))
                        .lines()
                        .getFirst()
                        .unitCostBase()
                        .toPlainString())
                .as("1000 / 30, rounded half up at scale 6")
                .isEqualTo("33.333333");
    }

    @Test
    void pro02LinesAreAllDeliveriesOrAllReturnsAndNeverZero() {
        assertViolation(
                () -> receipt(UUID.randomUUID(), List.of(line(piece(SMALL), "5", "1"), line(piece(LARGE), "-5", "1"))),
                ProcurementErrorCode.MIXED_LINE_SIGNS);
        assertViolation(
                () -> receipt(null, List.of(line(piece(SMALL), "0", "1"))), ProcurementErrorCode.MIXED_LINE_SIGNS);
        assertViolation(
                () -> receipt(null, List.of(line(piece(SMALL), "-1", "1"))),
                ProcurementErrorCode.CORRECTION_TARGET_INVALID);
        assertViolation(
                () -> receipt(UUID.randomUUID(), List.of(line(piece(SMALL), "1", "1"))),
                ProcurementErrorCode.CORRECTION_TARGET_INVALID);
        assertViolation(
                () -> receipt(null, List.of(line(piece(SMALL), "1", "-1"))), PlatformErrorCode.VALIDATION_FAILED);
    }

    @Test
    void pro02APostedReceiptIsFinal() {
        GoodsReceipt receipt = receipt(null, List.of(line(piece(SMALL), "10", "38.00")));
        UUID user = UUID.randomUUID();
        receipt.post(AT, user);

        assertThat(receipt.status()).isEqualTo(ReceiptStatus.POSTED);
        assertThat(receipt.postedAt()).isEqualTo(AT);
        assertThat(receipt.postedBy()).isEqualTo(user);
        assertViolation(() -> receipt.post(AT, null), ProcurementErrorCode.GOODS_RECEIPT_STATE_INVALID);
        assertViolation(receipt::cancel, ProcurementErrorCode.GOODS_RECEIPT_STATE_INVALID);
        assertViolation(() -> receipt.changeNotes("late"), ProcurementErrorCode.GOODS_RECEIPT_STATE_INVALID);
        assertViolation(() -> receipt.changeReceivedAt(AT), ProcurementErrorCode.GOODS_RECEIPT_STATE_INVALID);
        assertViolation(
                () -> receipt.replaceLines(List.of(line(piece(SMALL), "1", "1")), ROUNDING, IDS),
                ProcurementErrorCode.GOODS_RECEIPT_STATE_INVALID);
    }

    @Test
    void aDraftCanBeChangedOrCancelled() {
        GoodsReceipt receipt = receipt(null, List.of(line(piece(SMALL), "10", "38.00")));
        Instant later = AT.plusSeconds(60);
        receipt.changeNotes("Two trays cracked");
        receipt.changeReceivedAt(later);
        receipt.replaceLines(List.of(line(piece(LARGE), "4", "46.50")), ROUNDING, IDS);

        assertThat(receipt.notes()).isEqualTo("Two trays cracked");
        assertThat(receipt.receivedAt()).isEqualTo(later);
        assertThat(receipt.total()).isEqualByComparingTo("186.00");
        receipt.cancel();
        assertThat(receipt.status()).isEqualTo(ReceiptStatus.CANCELLED);
        assertViolation(receipt::markInvoiced, ProcurementErrorCode.RECEIPT_NOT_POSTED);
    }

    @Test
    void pro05AReceiptIsInvoicedOnce() {
        GoodsReceipt receipt = posted(List.of(line(piece(SMALL), "10", "38.00")));
        receipt.markInvoiced();

        assertThat(receipt.invoiced()).isTrue();
        assertViolation(receipt::markInvoiced, ProcurementErrorCode.RECEIPT_ALREADY_INVOICED);
    }

    @Test
    void pro02AReturnSendsBackAtMostWhatWasReceivedLessEarlierReturns() {
        Purchasable small = piece(SMALL);
        GoodsReceipt delivered = posted(List.of(line(small, "300", "38.00"), line(piece(MEDIUM), "300", "42.00")));
        GoodsReceipt earlier = receipt(delivered.id(), List.of(line(small, "-200", "38.00")));
        earlier.post(AT, null);
        GoodsReceipt ret = receipt(delivered.id(), List.of(line(small, "-100", "38.00")));

        ret.requireReturnableFrom(delivered, List.of(earlier, ret));
        assertThat(ret.isReturn()).isTrue();
        assertThat(ret.total()).isEqualByComparingTo("-3800.00");
        GoodsReceipt tooMuch = receipt(delivered.id(), List.of(line(small, "-100.5", "38.00")));
        assertViolation(
                () -> tooMuch.requireReturnableFrom(delivered, List.of(earlier)),
                ProcurementErrorCode.CORRECTION_TARGET_INVALID);
        GoodsReceipt traysBack = receipt(delivered.id(), List.of(line(tray(MEDIUM), "-10", "1260.00")));
        traysBack.requireReturnableFrom(delivered, List.of(earlier));
        assertThat(traysBack.lines().getFirst().qtyBase()).isEqualByComparingTo(new BigDecimal("-300"));
        GoodsReceipt notDelivered = receipt(delivered.id(), List.of(line(piece(LARGE), "-1", "46.50")));
        assertViolation(
                () -> notDelivered.requireReturnableFrom(delivered, List.of()),
                ProcurementErrorCode.CORRECTION_TARGET_INVALID);
    }

    @Test
    void pro02OnlyAPostedDeliveryOfTheSameSupplierCanBeCorrected() {
        GoodsReceipt draft = receipt(null, List.of(line(piece(SMALL), "10", "38.00")));
        GoodsReceipt delivered = posted(List.of(line(piece(SMALL), "10", "38.00")));
        GoodsReceipt otherSupplier = GoodsReceipt.draft(
                UUID.randomUUID(),
                ProcurementFixtures.TENANT,
                "GRN-000009",
                UUID.randomUUID(),
                ProcurementFixtures.LOCATION,
                null,
                null,
                AT,
                "LKR",
                null,
                List.of(line(piece(SMALL), "10", "38.00")),
                ROUNDING,
                IDS);
        otherSupplier.post(AT, null);

        assertViolation(
                () -> receipt(draft.id(), List.of(line(piece(SMALL), "-1", "38.00")))
                        .requireReturnableFrom(draft, List.of()),
                ProcurementErrorCode.CORRECTION_TARGET_INVALID);
        assertViolation(
                () -> receipt(otherSupplier.id(), List.of(line(piece(SMALL), "-1", "38.00")))
                        .requireReturnableFrom(otherSupplier, List.of()),
                ProcurementErrorCode.CORRECTION_TARGET_INVALID);
        assertViolation(
                () -> receipt(UUID.randomUUID(), List.of(line(piece(SMALL), "-1", "38.00")))
                        .requireReturnableFrom(delivered, List.of()),
                ProcurementErrorCode.CORRECTION_TARGET_INVALID);
        GoodsReceipt ret = receipt(delivered.id(), List.of(line(piece(SMALL), "-1", "38.00")));
        ret.post(AT, null);
        assertViolation(
                () -> receipt(ret.id(), List.of(line(piece(SMALL), "-1", "38.00")))
                        .requireReturnableFrom(ret, List.of()),
                ProcurementErrorCode.CORRECTION_TARGET_INVALID);
        assertViolation(
                () -> delivered.requireReturnableFrom(delivered, List.of()),
                ProcurementErrorCode.CORRECTION_TARGET_INVALID);
        GoodsReceipt elsewhere = GoodsReceipt.draft(
                UUID.randomUUID(),
                ProcurementFixtures.TENANT,
                "GRN-000010",
                ProcurementFixtures.SUPPLIER,
                UUID.randomUUID(),
                null,
                delivered.id(),
                AT,
                "LKR",
                null,
                List.of(line(piece(SMALL), "-1", "38.00")),
                ROUNDING,
                IDS);
        assertViolation(
                () -> elsewhere.requireReturnableFrom(delivered, List.of()),
                ProcurementErrorCode.CORRECTION_TARGET_INVALID);
    }
}
