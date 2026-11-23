package com.bmp.inventory.domain;

import com.bmp.inventory.domain.model.BalanceKey;
import com.bmp.inventory.domain.model.MovementType;
import com.bmp.inventory.domain.model.StockItem;
import com.bmp.inventory.domain.model.StockUnit;
import com.bmp.inventory.domain.service.StockLine;
import com.bmp.shared.money.RoundingPolicy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** The pilot shop's oil and eggs as stock items, and helpers for stock lines. */
public final class InventoryFixtures {

    public static final RoundingPolicy ROUNDING = RoundingPolicy.defaultPolicy();
    public static final Instant NOW = Instant.parse("2026-11-23T09:00:00Z");
    public static final UUID SHOP = UUID.fromString("0193b000-0000-7000-8000-000000000401");
    public static final UUID OIL = UUID.fromString("0193c000-0000-7000-8000-000000000301");
    public static final UUID ML = UUID.fromString("0193c000-0000-7000-8000-000000000320");
    public static final UUID CAN = UUID.fromString("0193c000-0000-7000-8000-000000000321");
    public static final UUID BOTTLE = UUID.fromString("0193c000-0000-7000-8000-000000000322");
    public static final UUID EGG = UUID.fromString("0193c000-0000-7000-8000-000000000204");
    public static final UUID PIECE = UUID.fromString("0193c000-0000-7000-8000-000000000301");
    public static final UUID TRAY = UUID.fromString("0193c000-0000-7000-8000-000000000302");
    public static final BalanceKey SEALED = BalanceKey.of(OIL, SHOP, "SEALED");
    public static final BalanceKey OPEN = BalanceKey.of(OIL, SHOP, "OPEN");
    public static final BalanceKey EGGS = BalanceKey.of(EGG, SHOP, "DEFAULT");

    private InventoryFixtures() {}

    public static StockItem oil() {
        return new StockItem(
                OIL,
                UUID.randomUUID(),
                "OIL",
                "Cooking oil",
                "ml",
                List.of("SEALED", "OPEN"),
                "BREAK_ON_SALE",
                List.of(
                        new StockUnit(ML, "ml", BigDecimal.ONE, "OPEN", true, false, false, true),
                        new StockUnit(CAN, "can", new BigDecimal("19565.28"), "SEALED", false, true, true, true),
                        new StockUnit(BOTTLE, "bottle-750", new BigDecimal("750"), "OPEN", false, false, true, true)),
                false,
                false,
                true,
                true,
                1);
    }

    public static StockItem eggs() {
        return new StockItem(
                EGG,
                UUID.randomUUID(),
                "EGG-RED-L",
                "Eggs Red L",
                "piece",
                List.of("DEFAULT"),
                "PREPACKED",
                List.of(
                        new StockUnit(PIECE, "piece", BigDecimal.ONE, "DEFAULT", true, true, true, true),
                        new StockUnit(TRAY, "tray", new BigDecimal("30"), "DEFAULT", false, true, true, true)),
                false,
                false,
                false,
                true,
                1);
    }

    public static StockLine in(BalanceKey key, String qty, @Nullable String cost) {
        return new StockLine(
                key,
                MovementType.RECEIPT,
                new BigDecimal(qty),
                cost == null ? null : new BigDecimal(cost),
                true,
                "TEST",
                null,
                null,
                null,
                NOW);
    }

    public static StockLine out(BalanceKey key, String qty, boolean mayGoNegative) {
        return new StockLine(
                key,
                MovementType.SALE,
                new BigDecimal(qty).negate(),
                null,
                mayGoNegative,
                "TEST",
                null,
                null,
                null,
                NOW);
    }

    public static Supplier<UUID> ids() {
        AtomicLong next = new AtomicLong();
        return () -> new UUID(0x0193e000L, next.incrementAndGet());
    }
}
