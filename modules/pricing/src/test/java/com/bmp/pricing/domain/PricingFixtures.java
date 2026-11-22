package com.bmp.pricing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.pricing.domain.model.EffectType;
import com.bmp.pricing.domain.model.ListChannel;
import com.bmp.pricing.domain.model.Price;
import com.bmp.pricing.domain.model.PriceKey;
import com.bmp.pricing.domain.model.PriceList;
import com.bmp.pricing.domain.model.PriceRule;
import com.bmp.pricing.domain.model.RuleConditions;
import com.bmp.pricing.domain.model.RuleEffect;
import com.bmp.pricing.domain.model.RuleKind;
import com.bmp.pricing.domain.model.TaxRate;
import com.bmp.pricing.domain.model.VariantRef;
import com.bmp.shared.error.BmpException;
import com.bmp.shared.error.ErrorCode;
import com.bmp.shared.id.TenantId;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.jspecify.annotations.Nullable;

/** Lists, prices, rules, rates and variant replicas shared by the pricing domain tests. */
public final class PricingFixtures {

    public static final TenantId TENANT = TenantId.of(UUID.fromString("01934f5a-6d1c-7000-8000-000000000a01"));
    public static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");
    public static final LocalDate TODAY = LocalDate.parse("2026-11-21");
    public static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    public static final UUID EGG_PRODUCT = UUID.randomUUID();
    public static final UUID EGG = UUID.randomUUID();
    public static final UUID PIECE = UUID.randomUUID();
    public static final UUID TRAY = UUID.randomUUID();
    public static final UUID OIL = UUID.randomUUID();
    public static final UUID ML = UUID.randomUUID();
    public static final UUID BOTTLE = UUID.randomUUID();
    public static final UUID VAT = UUID.randomUUID();

    private PricingFixtures() {}

    public static PriceList list(String code, ListChannel channel, int priority) {
        return list(code, channel, priority, null, null, true);
    }

    public static PriceList list(
            String code,
            ListChannel channel,
            int priority,
            @Nullable UUID group,
            @Nullable UUID customer,
            boolean taxInclusive) {
        return PriceList.create(
                UUID.randomUUID(), TENANT, code, code, channel, group, customer, "LKR", taxInclusive, priority);
    }

    public static Price price(PriceList list, UUID variant, UUID unit, String amount) {
        return price(list, variant, unit, "1", amount, START, null);
    }

    public static Price price(
            PriceList list, UUID variant, UUID unit, String minQty, String amount, Instant from, @Nullable Instant to) {
        return Price.create(
                UUID.randomUUID(),
                TENANT.value(),
                list.id(),
                new PriceKey(variant, unit, new BigDecimal(minQty)),
                new BigDecimal(amount),
                from,
                to,
                1);
    }

    /** An egg (piece, tray of 30) with the VAT category, and oil (ml, 750 ml bottle) without tax. */
    public static List<VariantRef> variants() {
        return List.of(
                new VariantRef(
                        EGG,
                        EGG_PRODUCT,
                        VAT,
                        true,
                        1,
                        List.of(
                                new VariantRef.Unit(PIECE, "piece", BigDecimal.ONE, true, true, true),
                                new VariantRef.Unit(TRAY, "tray", new BigDecimal("30"), false, true, true))),
                new VariantRef(
                        OIL,
                        UUID.randomUUID(),
                        null,
                        true,
                        1,
                        List.of(
                                new VariantRef.Unit(ML, "ml", BigDecimal.ONE, true, false, true),
                                new VariantRef.Unit(BOTTLE, "bottle-750", new BigDecimal("750"), false, true, true))));
    }

    public static TaxRate vat(String ratePct) {
        return rate(VAT, "VAT", ratePct, false, LocalDate.parse("2026-01-01"), null);
    }

    public static TaxRate rate(
            UUID category, String code, String ratePct, boolean compound, LocalDate from, @Nullable LocalDate to) {
        return TaxRate.create(
                UUID.randomUUID(), TENANT, category, code, code, new BigDecimal(ratePct), compound, from, to);
    }

    public static PriceRule rule(
            String code, RuleKind kind, EffectType type, String value, int priority, boolean stackable) {
        return rule(
                code, kind, RuleConditions.ALWAYS, new RuleEffect(type, new BigDecimal(value)), priority, stackable);
    }

    public static PriceRule rule(
            String code, RuleKind kind, RuleConditions conditions, RuleEffect effect, int priority, boolean stackable) {
        return PriceRule.create(
                UUID.randomUUID(), TENANT, code, code, kind, conditions, effect, priority, stackable, null, null);
    }

    public static void assertCode(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        BmpException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
    }
}
