package com.bmp.procurement.domain.model;

import com.bmp.shared.id.TenantId;
import com.bmp.shared.money.RoundingPolicy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** Units, orders and receipts for the procurement domain tests. */
final class ProcurementFixtures {

    static final TenantId TENANT = TenantId.of(UUID.fromString("0193a000-0000-7000-8000-000000000001"));
    static final UUID SUPPLIER = UUID.fromString("0193d000-0000-7000-8000-000000000101");
    static final UUID LOCATION = UUID.fromString("0193b000-0000-7000-8000-000000000401");
    static final RoundingPolicy ROUNDING = RoundingPolicy.defaultPolicy();
    static final Instant AT = Instant.parse("2026-11-23T09:00:00Z");
    static final Supplier<UUID> IDS = UUID::randomUUID;

    /** One egg of a size: the base unit. */
    static Purchasable piece(UUID variant) {
        return new Purchasable(variant, UUID.randomUUID(), BigDecimal.ONE, "NONE", false);
    }

    static Purchasable tray(UUID variant) {
        return new Purchasable(variant, UUID.randomUUID(), new BigDecimal("30"), "NONE", false);
    }

    static GoodsReceipt.Line line(Purchasable unit, String qty, String unitCost) {
        return new GoodsReceipt.Line(null, unit, new BigDecimal(qty), new BigDecimal(unitCost), null, null);
    }

    static GoodsReceipt receipt(@Nullable UUID corrects, List<GoodsReceipt.Line> lines) {
        return GoodsReceipt.draft(
                UUID.randomUUID(),
                TENANT,
                "GRN-000001",
                SUPPLIER,
                LOCATION,
                null,
                corrects,
                AT,
                "LKR",
                null,
                lines,
                ROUNDING,
                IDS);
    }

    static GoodsReceipt posted(List<GoodsReceipt.Line> lines) {
        GoodsReceipt receipt = receipt(null, lines);
        receipt.post(AT, null);
        return receipt;
    }

    private ProcurementFixtures() {}
}
