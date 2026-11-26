package com.bmp.inventory.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.inventory.domain.model.BalanceKey;
import com.bmp.inventory.domain.service.SaleReversal.Back;
import com.bmp.inventory.domain.service.SaleReversal.Booked;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SaleReversalTest {

    private static final UUID VARIANT = UUID.randomUUID();
    private static final UUID SHOP = UUID.randomUUID();
    private static final BalanceKey LOOSE = BalanceKey.of(VARIANT, SHOP, "DEFAULT");
    private static final BalanceKey EARLY = new BalanceKey(VARIANT, SHOP, "DEFAULT", UUID.randomUUID());
    private static final BalanceKey LATE = new BalanceKey(VARIANT, SHOP, "DEFAULT", UUID.randomUUID());
    private static final UUID LINE = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    private static Booked sold(UUID line, BalanceKey key, String qty, String cost) {
        return new Booked(line, key, new BigDecimal(qty).negate(), new BigDecimal(cost).negate());
    }

    private static Booked back(UUID line, BalanceKey key, String qty, String cost) {
        return new Booked(line, key, new BigDecimal(qty), new BigDecimal(cost));
    }

    private static void assertBack(Back back, BalanceKey key, String qty, String cost) {
        assertThat(back.key()).isEqualTo(key);
        assertThat(back.qtyBase()).isEqualByComparingTo(qty);
        assertThat(back.cost()).isEqualByComparingTo(cost);
    }

    @Test
    void aReturnComesBackAtTheCostTheSaleIssuedItWith() {
        List<Booked> booked = List.of(sold(LINE, LOOSE, "10", "380.0000"), sold(OTHER, LOOSE, "1", "9.9999"));

        List<Back> back = SaleReversal.restock(booked, LINE, new BigDecimal("2"));

        assertThat(back).singleElement().satisfies(b -> {
            assertThat(b.sourceLineId()).isEqualTo(LINE);
            assertBack(b, LOOSE, "2", "76.0000");
        });
    }

    @Test
    void theLastOfALineTakesWhatIsLeftOfItsCostSoNothingIsLostToRounding() {
        List<Booked> booked = List.of(sold(LINE, LOOSE, "3", "100.0000"), back(LINE, LOOSE, "1", "33.3333"));

        assertBack(SaleReversal.restock(booked, LINE, new BigDecimal("1")).getFirst(), LOOSE, "1", "33.3333");
        assertBack(SaleReversal.restock(booked, LINE, new BigDecimal("2")).getFirst(), LOOSE, "2", "66.6667");
    }

    @Test
    void returnsUndoTheIssueFromTheBatchTakenLastAndNeverPutBackMoreThanLeftABatch() {
        List<Booked> booked = List.of(sold(LINE, EARLY, "4", "160.0000"), sold(LINE, LATE, "6", "300.0000"));

        List<Back> seven = SaleReversal.restock(booked, LINE, new BigDecimal("7"));

        assertThat(seven).hasSize(2);
        assertBack(seven.get(0), LATE, "6", "300.0000");
        assertBack(seven.get(1), EARLY, "1", "40.0000");
    }

    @Test
    void moreBackThanWentOutGoesOntoTheBalanceTakenLastAtItsIssueCost() {
        List<Booked> booked = List.of(
                sold(LINE, EARLY, "4", "160.0000"),
                sold(LINE, LATE, "2", "100.0000"),
                back(LINE, LATE, "2", "100.0000"));

        List<Back> back = SaleReversal.restock(booked, LINE, new BigDecimal("5"));

        assertThat(back).hasSize(2);
        assertBack(back.get(0), EARLY, "4", "160.0000");
        assertBack(back.get(1), LATE, "1", "50.0000");
    }

    @Test
    void nothingComesBackForALineThatWasNotIssuedOrForNoQuantity() {
        List<Booked> booked = List.of(sold(OTHER, LOOSE, "1", "38.0000"));

        assertThat(SaleReversal.issued(booked, LINE)).isFalse();
        assertThat(SaleReversal.issued(booked, OTHER)).isTrue();
        assertThat(SaleReversal.issued(List.of(back(OTHER, LOOSE, "1", "38.0000")), OTHER))
                .as("a return alone is not an issue")
                .isFalse();
        assertThat(SaleReversal.restock(booked, LINE, BigDecimal.ONE)).isEmpty();
        assertThat(SaleReversal.restock(booked, OTHER, BigDecimal.ZERO)).isEmpty();
    }

    @Test
    void aVoidReversesWhatIsStillOutOfEveryLineAndBalanceAtIssueCost() {
        List<Booked> booked = List.of(
                sold(LINE, EARLY, "4", "160.0000"),
                sold(LINE, LATE, "6", "300.0000"),
                sold(OTHER, LOOSE, "2", "0.0000"),
                back(LINE, LATE, "6", "300.0000"),
                back(LINE, EARLY, "1", "40.0000"));

        List<Back> reversed = SaleReversal.reverseAll(booked);

        assertThat(reversed).hasSize(2);
        assertBack(reversed.get(0), EARLY, "3", "120.0000");
        assertThat(reversed.get(1).sourceLineId()).isEqualTo(OTHER);
        assertBack(reversed.get(1), LOOSE, "2", "0.0000");
        assertThat(SaleReversal.reverseAll(List.of(sold(LINE, LOOSE, "1", "5.0000"), back(LINE, LOOSE, "1", "5.0000"))))
                .as("nothing is left to reverse")
                .isEmpty();
    }

    @Test
    void aSaleIssuedAtNoCostComesBackAtNoCost() {
        List<Booked> booked = List.of(sold(LINE, LOOSE, "5", "0.0000"));

        assertBack(SaleReversal.restock(booked, LINE, new BigDecimal("2")).getFirst(), LOOSE, "2", "0.0000");
    }
}
