package com.bmp.pricing.domain.model;

import static com.bmp.pricing.domain.PricingFixtures.NOW;
import static com.bmp.pricing.domain.PricingFixtures.START;
import static com.bmp.pricing.domain.PricingFixtures.TENANT;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.pricing.domain.PricingFixtures;
import com.bmp.shared.id.UserId;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Audit stamps and the state other layers read from the pricing aggregates. */
class PricingAggregatesTest {

    private static final UserId OWNER = UserId.of(UUID.randomUUID());
    private static final Instant LATER = NOW.plusSeconds(60);

    @Test
    void aggregatesCarryTheirCreationAndModificationStamps() {
        UUID group = UUID.randomUUID();
        PriceList list = PriceList.create(
                UUID.randomUUID(), TENANT, "hotels", "Hotels", ListChannel.WHOLESALE, group, null, "LKR", false, 50);
        PriceRule rule = PriceRule.create(
                UUID.randomUUID(),
                TENANT,
                "promo",
                "Promo",
                RuleKind.PROMOTION,
                RuleConditions.ALWAYS,
                new RuleEffect(EffectType.PERCENT_OFF, BigDecimal.TEN),
                1,
                false,
                START,
                null);
        TaxCategory category = TaxCategory.create(UUID.randomUUID(), TENANT, "VAT", "VAT");
        TaxRate rate = TaxRate.create(
                UUID.randomUUID(),
                TENANT,
                category.id(),
                "VAT",
                "Value added tax",
                new BigDecimal("18"),
                false,
                LocalDate.parse("2026-01-01"),
                null);

        list.recordCreation(NOW, OWNER);
        rule.recordCreation(NOW, OWNER);
        category.recordCreation(NOW, null);
        rate.recordCreation(NOW, OWNER);
        list.recordModification(LATER, OWNER);
        rule.recordModification(LATER, null);
        category.recordModification(LATER, OWNER);
        rate.recordModification(LATER, OWNER);
        rate.rename("VAT 18");

        assertThat(List.of(list.createdAt(), rule.createdAt(), category.createdAt(), rate.createdAt()))
                .containsOnly(NOW);
        assertThat(List.of(list.version(), rule.version(), category.version(), rate.version()))
                .containsOnly(0L);
        assertThat(list.channel()).isEqualTo(ListChannel.WHOLESALE);
        assertThat(list.customerGroupId()).isEqualTo(group);
        assertThat(list.customerId()).isNull();
        assertThat(list.status()).isEqualTo(PricingStatus.ACTIVE);
        assertThat(list.currency()).isEqualTo("LKR");
        assertThat(rule.id()).isNotNull();
        assertThat(rule.validFrom()).isEqualTo(START);
        assertThat(rule.validTo()).isNull();
        assertThat(rule.status()).isEqualTo(PricingStatus.ACTIVE);
        assertThat(rule.kind()).isEqualTo(RuleKind.PROMOTION);
        assertThat(category.name()).isEqualTo("VAT");
        assertThat(rate.name()).isEqualTo("VAT 18");
        assertThat(rate.validFrom()).isEqualTo(LocalDate.parse("2026-01-01"));
        assertThat(rate.compound()).isFalse();
        Price price = PricingFixtures.price(list, PricingFixtures.EGG, PricingFixtures.PIECE, "48.00");
        assertThat(price.variantId()).isEqualTo(PricingFixtures.EGG);
        assertThat(price.overlaps(START.minusSeconds(10), START)).isFalse();
        assertThat(price.overlaps(START.minusSeconds(10), null)).isTrue();
    }

    @Test
    void ruleConditionsCheckEachDimension() {
        UUID list = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        UUID variant = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        RuleContext line = new RuleContext(
                SalesChannel.WHOLESALE, list, group, customer, product, variant, unit, new BigDecimal("300"));

        assertThat(RuleConditions.ALWAYS.matches(line)).isTrue();
        assertThat(conditions(SalesChannel.RETAIL, List.of(), List.of(), null).matches(line))
                .isFalse();
        assertThat(conditions(null, List.of(UUID.randomUUID()), List.of(), null).matches(line))
                .isFalse();
        assertThat(conditions(null, List.of(list), List.of(customer), new BigDecimal("300"))
                        .matches(line))
                .isTrue();
        assertThat(conditions(null, List.of(), List.of(), new BigDecimal("301")).matches(line))
                .isFalse();
        assertThat(conditions(null, List.of(), List.of(UUID.randomUUID()), null).matches(line))
                .isFalse();
        RuleContext anonymous =
                new RuleContext(SalesChannel.RETAIL, list, null, null, product, variant, unit, BigDecimal.ONE);
        assertThat(conditions(null, List.of(), List.of(customer), null).matches(anonymous))
                .as("a customer condition never matches a sale without a customer")
                .isFalse();
    }

    private static RuleConditions conditions(
            SalesChannel channel, List<UUID> lists, List<UUID> customers, BigDecimal minQtyBase) {
        return new RuleConditions(channel, lists, List.of(), customers, List.of(), List.of(), List.of(), minQtyBase);
    }
}
