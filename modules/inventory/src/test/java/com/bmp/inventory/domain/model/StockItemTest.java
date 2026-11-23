package com.bmp.inventory.domain.model;

import static com.bmp.inventory.domain.InventoryFixtures.BOTTLE;
import static com.bmp.inventory.domain.InventoryFixtures.CAN;
import static com.bmp.inventory.domain.InventoryFixtures.OIL;
import static com.bmp.inventory.domain.InventoryFixtures.SHOP;
import static com.bmp.inventory.domain.InventoryFixtures.TRAY;
import static com.bmp.inventory.domain.InventoryFixtures.eggs;
import static com.bmp.inventory.domain.InventoryFixtures.oil;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.inventory.domain.error.InventoryErrorCode;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.ErrorCategory;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StockItemTest {

    @Test
    void inv05QuantitiesInAPackagingUnitBecomeBaseUnitsInTheUnitsState() {
        assertThat(oil().resolve(CAN, null, new BigDecimal("10")))
                .isEqualTo(new ResolvedQuantity(new BigDecimal("195652.800000"), "SEALED"));
        assertThat(eggs().resolve(TRAY, null, new BigDecimal("10")).qtyBase()).isEqualByComparingTo("300");
        assertThat(eggs().resolve(null, null, new BigDecimal("6")))
                .as("the base unit when none is named")
                .isEqualTo(new ResolvedQuantity(new BigDecimal("6.000000"), "DEFAULT"));
    }

    @Test
    void inv06BreakOnSaleIssuesFromTheUnitsStateInBaseUnits() {
        ResolvedQuantity bottle = oil().resolve(BOTTLE, null, new BigDecimal("2"));

        assertThat(bottle.qtyBase()).isEqualByComparingTo("1500");
        assertThat(bottle.stockState()).isEqualTo("OPEN");
        assertThat(oil().resolve(CAN, "OPEN", BigDecimal.ONE).stockState())
                .as("a named state wins over the unit's")
                .isEqualTo("OPEN");
        assertThat(oil().sellMode()).isEqualTo("BREAK_ON_SALE");
    }

    @Test
    void unknownUnitsAndStatesAreRefused() {
        assertThatThrownBy(() -> oil().resolve(UUID.randomUUID(), null, BigDecimal.ONE))
                .isInstanceOfSatisfying(
                        BusinessRuleViolation.class,
                        e -> assertThat(e.errorCode()).isEqualTo(InventoryErrorCode.UNKNOWN_PACKAGING_UNIT));
        assertThatThrownBy(() -> oil().resolve(CAN, "DEFAULT", BigDecimal.ONE))
                .isInstanceOfSatisfying(
                        BusinessRuleViolation.class,
                        e -> assertThat(e.errorCode()).isEqualTo(InventoryErrorCode.INVALID_STOCK_STATE));
        StockItem noBase = new StockItem(
                OIL,
                UUID.randomUUID(),
                "X",
                "X",
                "ml",
                List.of("OPEN"),
                "PREPACKED",
                List.of(),
                false,
                false,
                false,
                true,
                1);
        assertThatThrownBy(() -> noBase.resolve(null, null, BigDecimal.ONE)).isInstanceOf(BusinessRuleViolation.class);
        assertThat(oil().hasState(null)).isFalse();
        assertThat(oil().baseUnit()).isPresent();
        assertThat(InventoryErrorCode.INSUFFICIENT_STOCK.category()).isEqualTo(ErrorCategory.CONFLICT);
        assertThat(InventoryErrorCode.INSUFFICIENT_STOCK.module()).isEqualTo("inventory");
        assertThat(InventoryErrorCode.INSUFFICIENT_STOCK.code()).isEqualTo("INSUFFICIENT_STOCK");
    }

    @Test
    void balanceKeysSortInLockOrder() {
        UUID a = new UUID(0, 1);
        UUID b = new UUID(0, 2);
        List<BalanceKey> keys = new ArrayList<>(List.of(
                new BalanceKey(b, SHOP, "OPEN", BalanceKey.NO_BATCH),
                new BalanceKey(a, SHOP, "SEALED", BalanceKey.NO_BATCH),
                new BalanceKey(a, SHOP, "OPEN", b),
                new BalanceKey(a, SHOP, "OPEN", a)));

        keys.sort(null);

        assertThat(keys).extracting(BalanceKey::variantId).containsExactly(a, a, a, b);
        assertThat(keys.get(0).stockState()).isEqualTo("OPEN");
        assertThat(keys.get(0).batchId()).isEqualTo(a);
        assertThat(keys.get(0).hasBatch()).isTrue();
        assertThat(BalanceKey.of(a, SHOP, "OPEN").hasBatch()).isFalse();
        assertThat(BalanceKey.of(a, SHOP, "OPEN").withBatch(b).batchId()).isEqualTo(b);
        assertThat(WastageReason.OTHER.needsNote()).isTrue();
        assertThat(WastageReason.BREAKAGE.needsNote()).isFalse();
        assertThat(new CostLayer(a, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, java.time.Instant.EPOCH)
                        .isEmpty())
                .isTrue();
    }
}
