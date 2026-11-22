package com.bmp.pricing.domain.service;

import static com.bmp.pricing.domain.PricingFixtures.BOTTLE;
import static com.bmp.pricing.domain.PricingFixtures.EGG;
import static com.bmp.pricing.domain.PricingFixtures.EGG_PRODUCT;
import static com.bmp.pricing.domain.PricingFixtures.ML;
import static com.bmp.pricing.domain.PricingFixtures.NOW;
import static com.bmp.pricing.domain.PricingFixtures.OIL;
import static com.bmp.pricing.domain.PricingFixtures.PIECE;
import static com.bmp.pricing.domain.PricingFixtures.START;
import static com.bmp.pricing.domain.PricingFixtures.TODAY;
import static com.bmp.pricing.domain.PricingFixtures.TRAY;
import static com.bmp.pricing.domain.PricingFixtures.list;
import static com.bmp.pricing.domain.PricingFixtures.price;
import static com.bmp.pricing.domain.PricingFixtures.rate;
import static com.bmp.pricing.domain.PricingFixtures.rule;
import static com.bmp.pricing.domain.PricingFixtures.variants;
import static com.bmp.pricing.domain.PricingFixtures.vat;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.pricing.domain.PricingFixtures;
import com.bmp.pricing.domain.model.EffectType;
import com.bmp.pricing.domain.model.ListChannel;
import com.bmp.pricing.domain.model.Price;
import com.bmp.pricing.domain.model.PriceList;
import com.bmp.pricing.domain.model.PriceRule;
import com.bmp.pricing.domain.model.RuleConditions;
import com.bmp.pricing.domain.model.RuleEffect;
import com.bmp.pricing.domain.model.RuleKind;
import com.bmp.pricing.domain.model.SalesChannel;
import com.bmp.pricing.domain.model.TaxRate;
import com.bmp.pricing.domain.model.VariantRef;
import com.bmp.shared.money.CurrencyCode;
import com.bmp.shared.money.RoundingPolicy;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class PriceResolverTest {

    private final PriceList retail = list("RETAIL", ListChannel.RETAIL, 100);
    private final PriceList wholesale = list("WHOLESALE", ListChannel.WHOLESALE, 100);

    private static PricingData data(
            boolean derive, List<PriceList> lists, List<Price> prices, List<PriceRule> rules, List<TaxRate> rates) {
        Map<UUID, VariantRef> refs =
                variants().stream().collect(Collectors.toMap(VariantRef::variantId, Function.identity()));
        return new PricingData(
                CurrencyCode.LKR, RoundingPolicy.defaultPolicy(), derive, lists, prices, refs, rules, rates);
    }

    private static ResolutionRequest basket(SalesChannel channel, Instant at, ResolutionRequest.Line... lines) {
        return basket(channel, null, null, at, lines);
    }

    private static ResolutionRequest basket(
            SalesChannel channel,
            @Nullable UUID customer,
            @Nullable UUID group,
            Instant at,
            ResolutionRequest.Line... lines) {
        return new ResolutionRequest(channel, customer, group, at, TODAY, List.of(lines));
    }

    private static ResolutionRequest.Line line(UUID variant, UUID unit, String qty) {
        return new ResolutionRequest.Line("1", variant, unit, new BigDecimal(qty));
    }

    private static ResolvedLine only(ResolvedBasket basket) {
        assertThat(basket.lines()).hasSize(1);
        return basket.lines().getFirst();
    }

    @Test
    void prc04TheCustomerListBeatsTheGroupListBeatsTheChannelListBeatsAnAnyList() {
        UUID customer = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        PriceList forCustomer = list("CUSTOMER", ListChannel.ANY, 500, null, customer, true);
        PriceList forGroup = list("HOTELS", ListChannel.RETAIL, 1, group, null, true);
        PriceList any = list("ANY", ListChannel.ANY, 1);
        List<PriceList> lists = List.of(any, forGroup, retail, forCustomer);
        List<Price> prices = List.of(
                price(any, EGG, PIECE, "60.00"),
                price(retail, EGG, PIECE, "55.00"),
                price(forGroup, EGG, PIECE, "50.00"),
                price(forCustomer, EGG, PIECE, "45.00"));
        PricingData data = data(false, lists, prices, List.of(), List.of());

        assertThat(only(PriceResolver.resolve(
                                basket(SalesChannel.RETAIL, customer, group, NOW, line(EGG, PIECE, "1")), data))
                        .unitPrice()
                        .amount())
                .isEqualByComparingTo("45.00");
        assertThat(only(PriceResolver.resolve(
                                basket(SalesChannel.RETAIL, null, group, NOW, line(EGG, PIECE, "1")), data))
                        .unitPrice()
                        .amount())
                .isEqualByComparingTo("50.00");
        assertThat(only(PriceResolver.resolve(basket(SalesChannel.RETAIL, NOW, line(EGG, PIECE, "1")), data))
                        .unitPrice()
                        .amount())
                .isEqualByComparingTo("55.00");
        assertThat(only(PriceResolver.resolve(basket(SalesChannel.WHOLESALE, NOW, line(EGG, PIECE, "1")), data))
                        .unitPrice()
                        .amount())
                .as("no wholesale list: the ANY list")
                .isEqualByComparingTo("60.00");
    }

    @Test
    void prc04WithinATierListsAreTriedByAscendingPriorityThenCode() {
        PriceList second = list("B-RETAIL", ListChannel.RETAIL, 10);
        PriceList first = list("A-RETAIL", ListChannel.RETAIL, 10);
        PriceList urgent = list("Z-RETAIL", ListChannel.RETAIL, 5);
        List<Price> prices = List.of(
                price(second, EGG, PIECE, "52.00"),
                price(first, EGG, PIECE, "51.00"),
                price(retail, EGG, PIECE, "55.00"));

        ResolvedLine byCode = only(PriceResolver.resolve(
                basket(SalesChannel.RETAIL, NOW, line(EGG, PIECE, "1")),
                data(false, List.of(retail, second, first), prices, List.of(), List.of())));
        ResolvedLine byPriority = only(PriceResolver.resolve(
                basket(SalesChannel.RETAIL, NOW, line(EGG, PIECE, "1")),
                data(false, List.of(retail, second, first, urgent), prices, List.of(), List.of())));

        assertThat(byCode.unitPrice().amount()).isEqualByComparingTo("51.00");
        assertThat(byPriority.priceListId())
                .as("the urgent list has no egg price, so the next list prices it")
                .isEqualTo(first.id());
    }

    @Test
    void prc04TheHighestMinimumQuantityNotAboveTheQuantityWins() {
        List<Price> prices = List.of(
                price(wholesale, EGG, PIECE, "48.00"),
                price(wholesale, EGG, PIECE, "300", "46.00", START, null),
                price(wholesale, EGG, PIECE, "1000", "44.00", START, null));
        PricingData data = data(false, List.of(wholesale), prices, List.of(), List.of());

        assertThat(only(PriceResolver.resolve(basket(SalesChannel.WHOLESALE, NOW, line(EGG, PIECE, "299")), data))
                        .unitPrice()
                        .amount())
                .isEqualByComparingTo("48.00");
        assertThat(only(PriceResolver.resolve(basket(SalesChannel.WHOLESALE, NOW, line(EGG, PIECE, "300")), data))
                        .unitPrice()
                        .amount())
                .isEqualByComparingTo("46.00");
        assertThat(only(PriceResolver.resolve(basket(SalesChannel.WHOLESALE, NOW, line(EGG, PIECE, "5000")), data))
                        .unitPrice()
                        .amount())
                .isEqualByComparingTo("44.00");
    }

    @Test
    void prc04ValidityIncludesItsStartAndExcludesItsEnd() {
        Instant change = Instant.parse("2026-11-21T00:00:00Z");
        List<Price> prices = List.of(
                price(retail, EGG, PIECE, "1", "50.00", START, change),
                price(retail, EGG, PIECE, "1", "55.00", change, null));
        PricingData data = data(false, List.of(retail), prices, List.of(), List.of());

        assertThat(only(PriceResolver.resolve(basket(SalesChannel.RETAIL, change, line(EGG, PIECE, "1")), data))
                        .unitPrice()
                        .amount())
                .isEqualByComparingTo("55.00");
        assertThat(only(PriceResolver.resolve(
                                basket(SalesChannel.RETAIL, change.minusMillis(1), line(EGG, PIECE, "1")), data))
                        .unitPrice()
                        .amount())
                .isEqualByComparingTo("50.00");
        assertThat(only(PriceResolver.resolve(
                                basket(SalesChannel.RETAIL, START.minusSeconds(1), line(EGG, PIECE, "1")), data))
                        .status())
                .isEqualTo(ResolvedLine.Status.PRICE_NOT_FOUND);
    }

    @Test
    void prc04InactiveListsAndListsOfOtherCustomersNeverPrice() {
        PriceList stranger = list("STRANGER", ListChannel.ANY, 1, null, UUID.randomUUID(), true);
        PriceList otherGroup = list("OTHER", ListChannel.ANY, 1, UUID.randomUUID(), null, true);
        PriceList closed = list("CLOSED", ListChannel.RETAIL, 1);
        closed.deactivate();
        List<Price> prices = List.of(
                price(stranger, EGG, PIECE, "1.00"),
                price(otherGroup, EGG, PIECE, "2.00"),
                price(closed, EGG, PIECE, "3.00"),
                price(retail, EGG, PIECE, "55.00"));

        ResolvedLine line = only(PriceResolver.resolve(
                basket(SalesChannel.RETAIL, UUID.randomUUID(), UUID.randomUUID(), NOW, line(EGG, PIECE, "1")),
                data(false, List.of(stranger, otherGroup, closed, retail), prices, List.of(), List.of())));

        assertThat(line.unitPrice().amount()).isEqualByComparingTo("55.00");
    }

    @Test
    void prc05DerivesAUnitPriceFromTheBaseUnitOnlyWhenTheTenantAllowsIt() {
        List<Price> prices = List.of(price(retail, EGG, PIECE, "55.00"));

        ResolvedLine derived = only(PriceResolver.resolve(
                basket(SalesChannel.RETAIL, NOW, line(EGG, TRAY, "2")),
                data(true, List.of(retail), prices, List.of(), List.of())));
        ResolvedBasket refused = PriceResolver.resolve(
                basket(SalesChannel.RETAIL, NOW, line(EGG, TRAY, "2"), line(EGG, PIECE, "1")),
                data(false, List.of(retail), prices, List.of(), List.of()));

        assertThat(derived.status()).isEqualTo(ResolvedLine.Status.PRICED);
        assertThat(derived.source()).isEqualTo("DERIVED");
        assertThat(derived.unitPrice().amount()).isEqualByComparingTo("1650.00");
        assertThat(derived.lineTotal().amount()).isEqualByComparingTo("3300.00");
        assertThat(refused.lines().getFirst().status())
                .as("a missing price is a line status, the basket is still answered")
                .isEqualTo(ResolvedLine.Status.PRICE_NOT_FOUND);
        assertThat(refused.lines().get(1).status()).isEqualTo(ResolvedLine.Status.PRICED);
        assertThat(refused.lineTotal().amount()).isEqualByComparingTo("55.00");
    }

    @Test
    void prc05ADerivedUnitPriceIsRoundedPerPolicy() {
        PriceList oilList = list("OIL", ListChannel.RETAIL, 1);
        List<Price> prices = List.of(price(oilList, OIL, ML, "0.8267"));

        ResolvedLine bottle = only(PriceResolver.resolve(
                basket(SalesChannel.RETAIL, NOW, line(OIL, BOTTLE, "1")),
                data(true, List.of(oilList), prices, List.of(), List.of())));

        assertThat(bottle.unitPrice().amount())
                .as("0.8267 × 750 = 620.025, half up")
                .isEqualByComparingTo("620.03");
    }

    @Test
    void prc06RulesApplyAfterTheListPriceInPriorityOrder() {
        PriceRule markup = rule("MARKUP", RuleKind.MARKUP, EffectType.MARKUP_PERCENT, "10", 20, false);
        PriceRule breakAt300 = rule(
                "EGG-300",
                RuleKind.QTY_BREAK,
                new RuleConditions(
                        SalesChannel.WHOLESALE,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(EGG_PRODUCT),
                        List.of(),
                        List.of(),
                        new BigDecimal("300")),
                new RuleEffect(EffectType.PERCENT_OFF, new BigDecimal("5")),
                10,
                false);
        PricingData data = data(
                false,
                List.of(wholesale),
                List.of(price(wholesale, EGG, TRAY, "1440.00")),
                List.of(markup, breakAt300),
                List.of());

        ResolvedLine tenTrays =
                only(PriceResolver.resolve(basket(SalesChannel.WHOLESALE, NOW, line(EGG, TRAY, "10")), data));
        ResolvedLine nineTrays =
                only(PriceResolver.resolve(basket(SalesChannel.WHOLESALE, NOW, line(EGG, TRAY, "9")), data));

        assertThat(tenTrays.appliedRules()).containsExactly("EGG-300", "MARKUP");
        assertThat(tenTrays.source()).isEqualTo("RULE:MARKUP");
        assertThat(tenTrays.amount().amount()).as("1440 × 0.95 × 1.10 × 10").isEqualByComparingTo("15048.00");
        assertThat(tenTrays.discount().amount()).isEqualByComparingTo("-648.00");
        assertThat(nineTrays.appliedRules())
                .as("270 pieces stay below the break")
                .containsExactly("MARKUP");
    }

    @Test
    void prc06AtMostOnePromotionAppliesUnlessTheNextIsStackable() {
        PriceRule tenOff = rule("TEN", RuleKind.PROMOTION, EffectType.PERCENT_OFF, "10", 1, false);
        PriceRule fiveOff = rule("FIVE", RuleKind.PROMOTION, EffectType.AMOUNT_OFF, "5", 2, false);
        PriceRule loyalty = rule("LOYAL", RuleKind.PROMOTION, EffectType.AMOUNT_OFF, "1", 3, true);
        PricingData data = data(
                false,
                List.of(retail),
                List.of(price(retail, EGG, PIECE, "50.00")),
                List.of(loyalty, fiveOff, tenOff),
                List.of());

        ResolvedLine line = only(PriceResolver.resolve(basket(SalesChannel.RETAIL, NOW, line(EGG, PIECE, "1")), data));

        assertThat(line.appliedRules()).containsExactly("TEN", "LOYAL");
        assertThat(line.amount().amount()).isEqualByComparingTo("44.00");
    }

    @Test
    void prc06APriceNeverGoesBelowZero() {
        PriceRule huge = rule("HUGE", RuleKind.PROMOTION, EffectType.AMOUNT_OFF, "80", 1, false);
        PricingData data = data(
                false, List.of(retail), List.of(price(retail, EGG, PIECE, "50.00")), List.of(huge), List.of(vat("18")));

        ResolvedLine line = only(PriceResolver.resolve(basket(SalesChannel.RETAIL, NOW, line(EGG, PIECE, "2")), data));

        assertThat(line.amount().amount()).isEqualByComparingTo("0.00");
        assertThat(line.tax().amount()).isEqualByComparingTo("0.00");
        assertThat(line.discount().amount()).isEqualByComparingTo("100.00");
    }

    @Test
    void prc06RulesOnlyApplyWhenEveryConditionHoldsAndTheyAreValid() {
        UUID group = UUID.randomUUID();
        PriceRule forGroup = rule(
                "GROUP",
                RuleKind.CUSTOMER_GROUP,
                new RuleConditions(
                        null,
                        List.of(retail.id()),
                        List.of(group),
                        List.of(),
                        List.of(),
                        List.of(EGG),
                        List.of(PIECE),
                        null),
                new RuleEffect(EffectType.FIXED_PRICE, new BigDecimal("40")),
                1,
                false);
        PriceRule expired = PriceRule.create(
                UUID.randomUUID(),
                PricingFixtures.TENANT,
                "OLD",
                "Old",
                RuleKind.PROMOTION,
                RuleConditions.ALWAYS,
                new RuleEffect(EffectType.PERCENT_OFF, new BigDecimal("50")),
                1,
                false,
                START,
                NOW);
        PriceRule inactive = rule("OFF", RuleKind.MARKUP, EffectType.MARKUP_PERCENT, "100", 1, false);
        inactive.deactivate();
        PricingData data = data(
                false,
                List.of(retail),
                List.of(price(retail, EGG, PIECE, "55.00"), price(retail, EGG, TRAY, "1600.00")),
                List.of(forGroup, expired, inactive),
                List.of());

        ResolvedLine member =
                only(PriceResolver.resolve(basket(SalesChannel.RETAIL, null, group, NOW, line(EGG, PIECE, "1")), data));
        ResolvedLine stranger =
                only(PriceResolver.resolve(basket(SalesChannel.RETAIL, NOW, line(EGG, PIECE, "1")), data));
        ResolvedLine tray =
                only(PriceResolver.resolve(basket(SalesChannel.RETAIL, null, group, NOW, line(EGG, TRAY, "1")), data));

        assertThat(member.amount().amount()).isEqualByComparingTo("40.00");
        assertThat(stranger.appliedRules()).isEmpty();
        assertThat(stranger.source()).isEqualTo("LIST");
        assertThat(tray.appliedRules()).as("the rule is for pieces only").isEmpty();
    }

    @Test
    void prc07TaxInclusivePricesAreBackCalculatedPerLine() {
        PricingData data = data(
                false, List.of(retail), List.of(price(retail, EGG, PIECE, "55.00")), List.of(), List.of(vat("18")));

        ResolvedLine line = only(PriceResolver.resolve(basket(SalesChannel.RETAIL, NOW, line(EGG, PIECE, "10")), data));

        assertThat(line.taxInclusive()).isTrue();
        assertThat(line.lineTotal().amount()).isEqualByComparingTo("550.00");
        assertThat(line.tax().amount()).as("550 × 18 / 118").isEqualByComparingTo("83.90");
        assertThat(line.net().amount()).isEqualByComparingTo("466.10");
        assertThat(line.taxes())
                .singleElement()
                .satisfies(t -> assertThat(t.code()).isEqualTo("VAT"));
    }

    @Test
    void prc07TaxExclusivePricesGetTheTaxOnTop() {
        PriceList net = list("NET", ListChannel.WHOLESALE, 1, null, null, false);
        PricingData data =
                data(false, List.of(net), List.of(price(net, EGG, PIECE, "40.00")), List.of(), List.of(vat("18")));

        ResolvedLine line =
                only(PriceResolver.resolve(basket(SalesChannel.WHOLESALE, NOW, line(EGG, PIECE, "3")), data));

        assertThat(line.net().amount()).isEqualByComparingTo("120.00");
        assertThat(line.tax().amount()).isEqualByComparingTo("21.60");
        assertThat(line.lineTotal().amount()).isEqualByComparingTo("141.60");
    }

    @Test
    void prc07OnlyRatesValidOnTheBusinessDateApply() {
        TaxRate later = rate(PricingFixtures.VAT, "VAT", "20", false, TODAY.plusDays(1), null);
        TaxRate earlier = rate(PricingFixtures.VAT, "VAT", "15", false, LocalDate.parse("2025-01-01"), TODAY);
        TaxRate otherCategory = rate(UUID.randomUUID(), "SSCL", "2.5", false, LocalDate.parse("2025-01-01"), null);
        PricingData data = data(
                false,
                List.of(retail),
                List.of(price(retail, EGG, PIECE, "118.00")),
                List.of(),
                List.of(later, earlier, otherCategory, vat("18")));

        ResolvedLine line = only(PriceResolver.resolve(basket(SalesChannel.RETAIL, NOW, line(EGG, PIECE, "1")), data));

        assertThat(line.taxes()).extracting(t -> t.ratePct().toPlainString()).containsExactly("18");
        assertThat(line.tax().amount()).isEqualByComparingTo("18.00");
    }

    @Test
    void prc10LinesCarryTheListTheVersionAndTheSource() {
        retail.nextVersion();
        retail.nextVersion();
        PricingData data =
                data(false, List.of(retail), List.of(price(retail, EGG, PIECE, "55.00")), List.of(), List.of());

        ResolvedLine line = only(PriceResolver.resolve(basket(SalesChannel.RETAIL, NOW, line(EGG, PIECE, "1")), data));

        assertThat(line.priceListId()).isEqualTo(retail.id());
        assertThat(line.priceListVersion()).isEqualTo(2);
        assertThat(line.source()).isEqualTo("LIST");
        assertThat(line.lineId()).isEqualTo("1");
    }

    @Test
    void unknownInactiveOrUnsoldUnitsAreNotSellable() {
        PricingData data = data(true, List.of(retail), List.of(price(retail, OIL, ML, "1.00")), List.of(), List.of());

        ResolvedBasket basket = PriceResolver.resolve(
                basket(
                        SalesChannel.RETAIL,
                        NOW,
                        line(UUID.randomUUID(), PIECE, "1"),
                        line(EGG, UUID.randomUUID(), "1"),
                        line(OIL, ML, "100")),
                data);

        assertThat(basket.lines()).extracting(ResolvedLine::status).containsOnly(ResolvedLine.Status.NOT_SELLABLE);
        assertThat(basket.lineTotal().isZero()).isTrue();
    }

    @Test
    void totalsAreSumsOfTheRoundedLines() {
        List<Price> prices =
                new ArrayList<>(List.of(price(retail, EGG, PIECE, "33.335"), price(retail, OIL, BOTTLE, "620.00")));
        PricingData data = data(false, List.of(retail), prices, List.of(), List.of(vat("18")));

        ResolvedBasket basket = PriceResolver.resolve(
                basket(SalesChannel.RETAIL, NOW, line(EGG, PIECE, "1"), line(OIL, BOTTLE, "2")), data);

        assertThat(basket.lines().getFirst().amount().amount())
                .as("x.xx5 rounds half up")
                .isEqualByComparingTo("33.34");
        assertThat(basket.lineTotal().amount()).isEqualByComparingTo("1273.34");
        assertThat(basket.tax().amount())
                .isEqualByComparingTo(basket.lines().getFirst().tax().amount());
        assertThat(basket.net().plus(basket.tax())).isEqualTo(basket.lineTotal());
        assertThat(basket.currency()).isEqualTo(CurrencyCode.LKR);
    }
}
