package com.bmp.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.catalog.domain.model.DefinedQuantity;
import com.bmp.catalog.domain.model.Dimension;
import com.bmp.catalog.domain.model.NewVariant;
import com.bmp.catalog.domain.model.PackagingUnitDraft;
import com.bmp.catalog.domain.model.SellMode;
import com.bmp.catalog.domain.model.StockStates;
import com.bmp.catalog.domain.model.UnitOfMeasure;
import com.bmp.catalog.domain.model.Variant;
import com.bmp.shared.error.BmpException;
import com.bmp.shared.error.ErrorCode;
import com.bmp.shared.id.ProductId;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.VariantId;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.jspecify.annotations.Nullable;

/** Units, drafts and assertions shared by the catalog domain tests. */
public final class CatalogFixtures {

    public static final TenantId TENANT = TenantId.of(UUID.fromString("01934f5a-6d1c-7000-8000-000000000a01"));

    public static final UnitOfMeasure PIECE = standard(1, "piece", Dimension.COUNT, "1");
    public static final UnitOfMeasure G = standard(2, "g", Dimension.MASS, "0.001");
    public static final UnitOfMeasure KG = standard(3, "kg", Dimension.MASS, "1");
    public static final UnitOfMeasure ML = standard(4, "ml", Dimension.VOLUME, "0.001");
    public static final UnitOfMeasure L = standard(5, "l", Dimension.VOLUME, "1");
    public static final UnitOfMeasure PACK = new UnitOfMeasure(id(8), null, "pack", "Pack", Dimension.COUNT, null);
    public static final UnitOfMeasure DOZEN = standard(9, "dozen", Dimension.COUNT, "12");

    public static final List<UnitOfMeasure> UNITS = List.of(PIECE, G, KG, ML, L, PACK, DOZEN);

    private CatalogFixtures() {}

    public static UUID id(int n) {
        return UUID.fromString(String.format("0193a000-0000-7000-8000-%012d", n));
    }

    /** Fresh ids for aggregates and their parts. */
    public static Supplier<UUID> ids() {
        return UUID::randomUUID;
    }

    public static PackagingUnitDraft base(String code, String state, boolean canBuy, boolean canSell) {
        return new PackagingUnitDraft(code, code, BigDecimal.ONE, null, state, true, canBuy, canSell, false);
    }

    public static PackagingUnitDraft unit(
            String code, String qtyInBase, String state, boolean canBuy, boolean canSell) {
        return new PackagingUnitDraft(
                code, code, new BigDecimal(qtyInBase), null, state, false, canBuy, canSell, false);
    }

    public static PackagingUnitDraft defined(
            String code, String qtyInBase, DefinedQuantity definedAs, String state, boolean canBuy, boolean canSell) {
        return new PackagingUnitDraft(
                code, code, new BigDecimal(qtyInBase), definedAs, state, false, canBuy, canSell, false);
    }

    /** An egg: base piece, tray of 30, both bought and sold. */
    public static Variant egg() {
        return variant(
                "EGG-RED-L",
                List.of(StockStates.DEFAULT),
                SellMode.PREPACKED,
                base("piece", StockStates.DEFAULT, true, true),
                unit("tray", "30", StockStates.DEFAULT, true, true));
    }

    /** Cooking oil: base ml (open), the 18 kg can (sealed) and a 750 ml bottle (open). */
    public static Variant oil() {
        return variant(
                "OIL",
                List.of(StockStates.SEALED, StockStates.OPEN),
                SellMode.BREAK_ON_SALE,
                base("ml", StockStates.OPEN, false, false),
                defined(
                        "can",
                        "19565.28",
                        new DefinedQuantity(new BigDecimal("18"), KG.id()),
                        StockStates.SEALED,
                        true,
                        true),
                unit("bottle-750", "750", StockStates.OPEN, false, true));
    }

    public static Variant variant(String sku, List<String> states, SellMode sellMode, PackagingUnitDraft... units) {
        return Variant.create(
                VariantId.of(UUID.randomUUID()),
                TENANT,
                ProductId.of(UUID.randomUUID()),
                new NewVariant(sku, sku, List.of(), states, sellMode, new ArrayList<>(List.of(units))),
                ids());
    }

    /** Asserts that {@code call} throws a platform exception with {@code code}. */
    public static void assertCode(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        BmpException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    private static UnitOfMeasure standard(int n, String code, Dimension dimension, @Nullable String factor) {
        return new UnitOfMeasure(id(n), null, code, code, dimension, factor != null ? new BigDecimal(factor) : null);
    }
}
