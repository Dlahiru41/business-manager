package com.bmp.inventory.domain.model;

import static com.bmp.inventory.domain.InventoryFixtures.EGG;
import static com.bmp.inventory.domain.InventoryFixtures.EGGS;
import static com.bmp.inventory.domain.InventoryFixtures.NOW;
import static com.bmp.inventory.domain.InventoryFixtures.SHOP;
import static com.bmp.inventory.domain.InventoryFixtures.ids;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.inventory.domain.error.InventoryErrorCode;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.id.TenantId;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StockCountTest {

    private static StockCount count(List<UUID> scope) {
        return StockCount.open(UUID.randomUUID(), TenantId.of(UUID.randomUUID()), SHOP, scope, "Month end", NOW);
    }

    private static void assertCode(Runnable change, InventoryErrorCode code) {
        assertThatThrownBy(change::run)
                .isInstanceOfSatisfying(
                        BusinessRuleViolation.class,
                        e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    @Test
    void inv12ARecountTakesTheSystemQuantityAtTheRecount() {
        StockCount count = count(List.of());
        AtomicInteger asked = new AtomicInteger();

        count.record(
                EGGS,
                new BigDecimal("290"),
                null,
                () -> {
                    asked.incrementAndGet();
                    return new BigDecimal("300");
                },
                ids(),
                NOW);
        count.record(
                EGGS,
                new BigDecimal("288"),
                "Two more broken",
                () -> {
                    asked.incrementAndGet();
                    return new BigDecimal("250");
                },
                ids(),
                NOW.plusSeconds(600));

        assertThat(asked).as("each count takes a snapshot").hasValue(2);
        StockCountLine line = count.lines().getFirst();
        assertThat(line.systemQty()).as("the stock at the recount").isEqualByComparingTo("250");
        assertThat(line.countedQty()).isEqualByComparingTo("288");
        assertThat(line.variance()).as("counted − system at the recount").isEqualByComparingTo("38");
        assertThat(line.varianceReason()).isEqualTo("Two more broken");
        assertThat(line.snapshotAt()).isEqualTo(NOW.plusSeconds(600));
        assertThat(line.key(SHOP)).isEqualTo(EGGS);
        assertThat(line.variantId()).isEqualTo(EGG);
        assertThat(line.stockState()).isEqualTo("DEFAULT");
        assertThat(line.batchId()).isEqualTo(BalanceKey.NO_BATCH);
        assertThat(count.note()).isEqualTo("Month end");
        assertThat(count.startedAt()).isEqualTo(NOW);
    }

    @Test
    void inv12CountsGoOpenSubmittedPostedAndNeverChangeOncePosted() {
        StockCount count = count(List.of(EGG));
        count.record(EGGS, new BigDecimal("290"), null, () -> new BigDecimal("300"), ids(), NOW);
        assertCode(() -> count.post(Map.of(), NOW, null), InventoryErrorCode.COUNT_STATE_INVALID);

        count.submit(NOW);
        assertCode(() -> count.submit(NOW), InventoryErrorCode.COUNT_STATE_INVALID);
        UUID line = count.lines().getFirst().id();
        UUID poster = UUID.randomUUID();
        count.post(Map.of(line, new BigDecimal("-425.00")), NOW, poster);

        assertThat(count.status()).isEqualTo(CountStatus.POSTED);
        assertThat(count.postedBy()).isEqualTo(poster);
        assertThat(count.postedAt()).isEqualTo(NOW);
        assertThat(count.submittedAt()).isEqualTo(NOW);
        assertThat(count.lines().getFirst().varianceQty()).isEqualByComparingTo("-10");
        assertThat(count.lines().getFirst().varianceCost()).isEqualByComparingTo("-425.00");
        assertCode(
                () -> count.record(EGGS, BigDecimal.ONE, null, () -> BigDecimal.ZERO, ids(), NOW),
                InventoryErrorCode.COUNT_ALREADY_POSTED);
        assertCode(count::cancel, InventoryErrorCode.COUNT_ALREADY_POSTED);
        assertCode(() -> count.submit(NOW), InventoryErrorCode.COUNT_ALREADY_POSTED);
    }

    @Test
    void countsCoverTheirScopeAndCanBeCancelledOnce() {
        StockCount count = count(List.of(UUID.randomUUID()));

        assertCode(
                () -> count.record(EGGS, BigDecimal.ONE, null, () -> BigDecimal.ZERO, ids(), NOW),
                InventoryErrorCode.UNKNOWN_VARIANT);
        count.cancel();
        count.cancel();
        assertThat(count.status()).isEqualTo(CountStatus.CANCELLED);
        assertThat(count.variantIds()).hasSize(1);
        assertThat(count.version()).isZero();
        assertThat(count.locationId()).isEqualTo(SHOP);
        assertCode(() -> count.submit(NOW), InventoryErrorCode.COUNT_STATE_INVALID);
    }
}
