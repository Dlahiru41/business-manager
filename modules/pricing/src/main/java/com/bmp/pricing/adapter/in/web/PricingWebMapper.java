package com.bmp.pricing.adapter.in.web;

import com.bmp.pricing.adapter.in.web.generated.model.LineTax;
import com.bmp.pricing.adapter.in.web.generated.model.Price;
import com.bmp.pricing.adapter.in.web.generated.model.PriceAmount;
import com.bmp.pricing.adapter.in.web.generated.model.PriceChange;
import com.bmp.pricing.adapter.in.web.generated.model.PriceList;
import com.bmp.pricing.adapter.in.web.generated.model.PriceListSnapshot;
import com.bmp.pricing.adapter.in.web.generated.model.PriceListVersion;
import com.bmp.pricing.adapter.in.web.generated.model.PriceRule;
import com.bmp.pricing.adapter.in.web.generated.model.ResolvedLine;
import com.bmp.pricing.adapter.in.web.generated.model.ResolvedPrices;
import com.bmp.pricing.adapter.in.web.generated.model.RuleConditions;
import com.bmp.pricing.adapter.in.web.generated.model.TaxCategory;
import com.bmp.pricing.adapter.in.web.generated.model.TaxRate;
import com.bmp.pricing.adapter.in.web.generated.model.Totals;
import com.bmp.pricing.application.query.PriceChangeView;
import com.bmp.pricing.application.query.PriceListVersionView;
import com.bmp.pricing.application.query.PriceListView;
import com.bmp.pricing.application.query.PriceRuleView;
import com.bmp.pricing.application.query.PriceSnapshotView;
import com.bmp.pricing.application.query.PriceView;
import com.bmp.pricing.application.query.TaxCategoryView;
import com.bmp.pricing.application.query.TaxRateView;
import com.bmp.pricing.domain.service.ResolvedBasket;
import com.bmp.shared.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/** Pricing read models to API representations; amounts and quantities become decimal strings. */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
interface PricingWebMapper {

    PriceList toDto(PriceListView view);

    @Mapping(target = "amount", expression = "java(money(view.amount(), view.currency()))")
    Price toDto(PriceView view);

    PriceListVersion toDto(PriceListVersionView view);

    PriceChange toDto(PriceChangeView view);

    @Mapping(target = "priceListId", source = "list.id")
    @Mapping(target = "code", source = "list.code")
    @Mapping(target = "channel", source = "list.channel")
    @Mapping(target = "customerGroupId", source = "list.customerGroupId")
    @Mapping(target = "customerId", source = "list.customerId")
    @Mapping(target = "currency", source = "list.currency")
    @Mapping(target = "taxInclusive", source = "list.taxInclusive")
    PriceListSnapshot toDto(PriceSnapshotView view);

    @Mapping(
            target = "effect",
            expression = "java(new com.bmp.pricing.adapter.in.web.generated.model.RuleEffect()"
                    + ".type(com.bmp.pricing.adapter.in.web.generated.model.EffectType.valueOf(view.effectType()))"
                    + ".value(decimal(view.effectValue())))")
    PriceRule toDto(PriceRuleView view);

    RuleConditions toDto(com.bmp.pricing.domain.model.RuleConditions conditions);

    TaxCategory toDto(TaxCategoryView view);

    TaxRate toDto(TaxRateView view);

    @Mapping(target = "totals", expression = "java(totals(basket))")
    ResolvedPrices toDto(ResolvedBasket basket);

    @Mapping(target = "netAmount", source = "net")
    @Mapping(target = "taxAmount", source = "tax")
    ResolvedLine toDto(com.bmp.pricing.domain.service.ResolvedLine line);

    LineTax toDto(com.bmp.pricing.domain.service.LineTax tax);

    default Totals totals(ResolvedBasket basket) {
        return new Totals()
                .netAmount(total(basket.net()))
                .taxAmount(total(basket.tax()))
                .discount(total(basket.discount()))
                .lineTotal(total(basket.lineTotal()));
    }

    private PriceAmount total(Money money) {
        return money(money.amount(), money.currency().code());
    }

    default @Nullable PriceAmount money(@Nullable Money money) {
        return money == null ? null : money(money.amount(), money.currency().code());
    }

    /** At least the currency's minor units, more when the amount has them (a price per ml: 0.8267). */
    default PriceAmount money(BigDecimal amount, String currency) {
        int minor = com.bmp.shared.money.CurrencyCode.of(currency).minorUnits();
        BigDecimal stripped = amount.stripTrailingZeros();
        BigDecimal shown = stripped.scale() < minor ? amount.setScale(minor, RoundingMode.HALF_UP) : stripped;
        return new PriceAmount().amount(shown.toPlainString()).currency(currency);
    }

    default String currency(com.bmp.shared.money.CurrencyCode currency) {
        return currency.code();
    }

    default @Nullable OffsetDateTime toOffsetDateTime(@Nullable Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    /** Decimal strings, never numbers (02 par. 5). */
    default @Nullable String decimal(@Nullable BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
}
