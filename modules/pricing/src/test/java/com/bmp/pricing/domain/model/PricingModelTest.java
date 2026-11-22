package com.bmp.pricing.domain.model;

import static com.bmp.pricing.domain.PricingFixtures.NOW;
import static com.bmp.pricing.domain.PricingFixtures.START;
import static com.bmp.pricing.domain.PricingFixtures.TENANT;
import static com.bmp.pricing.domain.PricingFixtures.assertCode;
import static com.bmp.pricing.domain.PricingFixtures.list;
import static com.bmp.pricing.domain.PricingFixtures.rate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.pricing.domain.PricingFixtures;
import com.bmp.pricing.domain.error.PricingErrorCode;
import com.bmp.shared.error.ValidationFailure;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PricingModelTest {

    @Test
    void prc01AListIsForACustomerGroupOrACustomerNotBoth() {
        assertCode(
                () -> PriceList.create(
                        UUID.randomUUID(),
                        TENANT,
                        "X",
                        "X",
                        ListChannel.ANY,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "LKR",
                        true,
                        1),
                PricingErrorCode.INVALID_PRICE_LIST_SCOPE);
        assertThat(list("retail", ListChannel.RETAIL, 1).code()).isEqualTo("RETAIL");
        assertThat(list("G", ListChannel.WHOLESALE, 1, UUID.randomUUID(), null, true)
                        .scope())
                .isEqualTo(ListScope.CUSTOMER_GROUP);
        assertThat(list("A", ListChannel.ANY, 1).scope()).isEqualTo(ListScope.ANY);
        assertThat(list("R", ListChannel.RETAIL, 1).scope()).isEqualTo(ListScope.CHANNEL);
    }

    @Test
    void prc01ChannelListsServeTheirChannelAndAnyListsServeBoth() {
        PriceList wholesale = list("W", ListChannel.WHOLESALE, 1);
        PriceList any = list("A", ListChannel.ANY, 1);
        UUID customer = UUID.randomUUID();
        PriceList forCustomer = list("C", ListChannel.RETAIL, 1, null, customer, true);

        assertThat(wholesale.serves(SalesChannel.WHOLESALE, null, null)).isTrue();
        assertThat(wholesale.serves(SalesChannel.RETAIL, null, null)).isFalse();
        assertThat(any.serves(SalesChannel.RETAIL, null, null)).isTrue();
        assertThat(forCustomer.serves(SalesChannel.RETAIL, customer, null)).isTrue();
        assertThat(forCustomer.serves(SalesChannel.WHOLESALE, customer, null)).isFalse();
        assertThat(forCustomer.serves(SalesChannel.RETAIL, UUID.randomUUID(), null))
                .isFalse();
        wholesale.deactivate();
        assertThat(wholesale.serves(SalesChannel.WHOLESALE, null, null)).isFalse();
        wholesale.activate();
        assertThat(wholesale.isActive()).isTrue();
    }

    @Test
    void prc02APriceStartsAtAPositiveMinimumQuantity() {
        UUID variant = UUID.randomUUID();
        UUID unit = UUID.randomUUID();

        assertThat(new PriceKey(variant, unit, new BigDecimal("0.001")).minQty())
                .as("a weighed unit may be priced from one gram of a kilogram")
                .isEqualByComparingTo("0.001");
        assertThatThrownBy(() -> new PriceKey(variant, unit, BigDecimal.ZERO)).isInstanceOf(ValidationFailure.class);
        assertThatThrownBy(() -> new PriceKey(variant, unit, new BigDecimal("0.0000001")))
                .as("rounds to zero at storage scale")
                .isInstanceOf(ValidationFailure.class);
    }

    @Test
    void prc03EveryPriceChangeTakesTheNextListVersion() {
        PriceList retail = list("R", ListChannel.RETAIL, 1);

        assertThat(retail.currentVersion()).isZero();
        assertThat(retail.nextVersion()).isEqualTo(1);
        assertThat(retail.nextVersion()).isEqualTo(2);
        retail.rename("Retail prices");
        retail.reprioritise(5);
        assertThat(retail.name()).isEqualTo("Retail prices");
        assertThat(retail.priority()).isEqualTo(5);
        assertThat(retail.taxInclusive()).isTrue();
    }

    @Test
    void prc06EffectsAreValidatedAndNeverGoBelowZero() {
        assertCode(
                () -> new RuleEffect(EffectType.PERCENT_OFF, new BigDecimal("101")),
                PricingErrorCode.INVALID_RULE_EFFECT);
        assertCode(
                () -> new RuleEffect(EffectType.AMOUNT_OFF, new BigDecimal("-1")),
                PricingErrorCode.INVALID_RULE_EFFECT);
        assertThat(new RuleEffect(EffectType.FIXED_PRICE, BigDecimal.TEN).apply(new BigDecimal("55")))
                .isEqualByComparingTo("10");
        assertThat(new RuleEffect(EffectType.AMOUNT_OFF, BigDecimal.TEN).apply(new BigDecimal("5")))
                .isEqualByComparingTo("0");
        assertThat(new RuleEffect(EffectType.MARKUP_PERCENT, BigDecimal.TEN).apply(new BigDecimal("50")))
                .isEqualByComparingTo("55");
    }

    @Test
    void prc06RulesHaveAValidityWindowAndCanBeChanged() {
        PriceRule rule = PricingFixtures.rule("R", RuleKind.PROMOTION, EffectType.PERCENT_OFF, "10", 1, false);

        assertCode(() -> rule.validity(NOW, START), PricingErrorCode.INVALID_VALIDITY);
        rule.validity(START, NOW);
        assertThat(rule.appliesAt(START)).isTrue();
        assertThat(rule.appliesAt(NOW)).isFalse();
        rule.validity(null, null);
        rule.rename("Ten off");
        rule.reprioritise(3, true);
        rule.changeEffect(new RuleEffect(EffectType.AMOUNT_OFF, BigDecimal.ONE));
        rule.changeConditions(RuleConditions.ALWAYS);
        rule.deactivate();
        assertThat(rule.appliesAt(NOW)).isFalse();
        rule.activate();
        assertThat(rule.appliesAt(NOW)).isTrue();
        assertThat(rule.stackable()).isTrue();
        assertThat(rule.name()).isEqualTo("Ten off");
    }

    @Test
    void prc07TaxRatesApplyOnTheirDatesAndNeverOverlapForOneCode() {
        LocalDate jan = LocalDate.parse("2026-01-01");
        LocalDate jul = LocalDate.parse("2026-07-01");
        TaxRate first = rate(PricingFixtures.VAT, "VAT", "15", false, jan, jul);
        TaxRate second = rate(PricingFixtures.VAT, "VAT", "18", false, jul, null);
        TaxRate overlapping = rate(PricingFixtures.VAT, "vat", "18", false, LocalDate.parse("2026-06-01"), null);
        TaxRate otherCode = rate(PricingFixtures.VAT, "SSCL", "2.5", false, jan, null);

        assertThat(first.appliesOn(jan)).isTrue();
        assertThat(first.appliesOn(jul)).isFalse();
        assertThat(second.appliesOn(jul)).isTrue();
        assertThat(first.overlaps(second)).isFalse();
        assertThat(first.overlaps(overlapping)).isTrue();
        assertThat(first.overlaps(otherCode)).isFalse();
        assertThat(first.overlaps(first)).isFalse();
        assertCode(() -> second.endOn(jul), PricingErrorCode.INVALID_VALIDITY);
        second.endOn(LocalDate.parse("2027-01-01"));
        assertThat(second.validTo()).isEqualTo(LocalDate.parse("2027-01-01"));
        assertThatThrownBy(() -> rate(PricingFixtures.VAT, "BAD", "120", false, jan, null))
                .isInstanceOf(ValidationFailure.class);
    }

    @Test
    void taxCategoriesAreRenamedAndDeactivated() {
        TaxCategory vat = TaxCategory.create(UUID.randomUUID(), TENANT, "vat_standard", "VAT");

        vat.rename("VAT standard");
        vat.deactivate();
        assertThat(vat.code()).isEqualTo("VAT_STANDARD");
        assertThat(vat.status()).isEqualTo(PricingStatus.INACTIVE);
        vat.activate();
        assertThat(vat.status()).isEqualTo(PricingStatus.ACTIVE);
    }

    @Test
    void variantRefsFindTheirUnits() {
        VariantRef egg = PricingFixtures.variants().getFirst();

        assertThat(egg.baseUnit()).map(VariantRef.Unit::code).hasValue("piece");
        assertThat(egg.unit(PricingFixtures.TRAY))
                .map(VariantRef.Unit::sellable)
                .hasValue(true);
        assertThat(egg.unit(UUID.randomUUID())).isEmpty();
        assertThat(new VariantRef.Unit(UUID.randomUUID(), "x", BigDecimal.ONE, false, true, false).sellable())
                .isFalse();
        assertThat(List.of(PriceChangeKind.values())).hasSize(3);
    }
}
