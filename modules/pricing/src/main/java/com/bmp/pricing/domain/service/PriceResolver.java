package com.bmp.pricing.domain.service;

import com.bmp.pricing.domain.model.Price;
import com.bmp.pricing.domain.model.PriceList;
import com.bmp.pricing.domain.model.PriceRule;
import com.bmp.pricing.domain.model.RuleContext;
import com.bmp.pricing.domain.model.RuleKind;
import com.bmp.pricing.domain.model.TaxRate;
import com.bmp.pricing.domain.model.VariantRef;
import com.bmp.shared.money.CurrencyCode;
import com.bmp.shared.money.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Prices a basket (PRC-04..PRC-07, PRC-10) as a pure function over loaded data:
 *
 * <ol>
 *   <li>candidate lists: the customer's, then the customer group's, then the channel's, then {@code ANY} lists, each
 *       tier by ascending priority (then code); the first list with a price valid at {@code at} for the unit wins, and
 *       within it the row with the highest {@code minQty} not above the quantity;
 *   <li>without one, and when the tenant allows it, the base unit's price × {@code qtyInBase}, rounded (DERIVED);
 *       otherwise the line is {@code PRICE_NOT_FOUND};
 *   <li>price rules by ascending priority; at most one promotion unless later ones are stackable; never below zero;
 *   <li>taxes of the variant's tax category on the business date, inclusive or exclusive as the list says.
 * </ol>
 */
public final class PriceResolver {

    private static final String LIST = "LIST";
    private static final String DERIVED = "DERIVED";
    private static final String RULE = "RULE:";

    private PriceResolver() {}

    public static ResolvedBasket resolve(ResolutionRequest request, PricingData data) {
        List<PriceList> candidates = candidates(request, data.lists());
        List<ResolvedLine> lines = new ArrayList<>();
        for (ResolutionRequest.Line line : request.lines()) {
            lines.add(line(request, line, candidates, data));
        }
        Money zero = Money.zero(data.currency());
        Money net = zero;
        Money tax = zero;
        Money discount = zero;
        Money total = zero;
        for (ResolvedLine line : lines) {
            if (line.status() == ResolvedLine.Status.PRICED) {
                net = net.plus(Objects.requireNonNull(line.net()));
                tax = tax.plus(Objects.requireNonNull(line.tax()));
                discount = discount.plus(Objects.requireNonNull(line.discount()));
                total = total.plus(Objects.requireNonNull(line.lineTotal()));
            }
        }
        return new ResolvedBasket(request.channel(), data.currency(), request.at(), lines, net, tax, discount, total);
    }

    /** Rule PRC-04: the lists that may price the basket, in the order they are tried. */
    static List<PriceList> candidates(ResolutionRequest request, List<PriceList> lists) {
        return lists.stream()
                .filter(l -> l.serves(request.channel(), request.customerId(), request.customerGroupId()))
                .sorted(Comparator.comparing(PriceList::scope)
                        .thenComparingInt(PriceList::priority)
                        .thenComparing(PriceList::code))
                .toList();
    }

    private static ResolvedLine line(
            ResolutionRequest request, ResolutionRequest.Line line, List<PriceList> candidates, PricingData data) {
        VariantRef variant = data.variants().get(line.variantId());
        Optional<VariantRef.Unit> unit = variant != null ? variant.unit(line.packagingUnitId()) : Optional.empty();
        if (variant == null
                || !variant.active()
                || unit.isEmpty()
                || !unit.get().sellable()) {
            return ResolvedLine.unpriced(line, ResolvedLine.Status.NOT_SELLABLE);
        }
        BigDecimal qtyBase = line.qty().multiply(unit.get().qtyInBase());
        Optional<Found> found = listPrice(candidates, data.prices(), unit.get().id(), line.qty(), request);
        if (found.isEmpty() && data.derivePriceFromBase() && !unit.get().isBase()) {
            found = variant.baseUnit()
                    .flatMap(base -> listPrice(candidates, data.prices(), base.id(), qtyBase, request))
                    .map(base -> new Found(
                            base.list(),
                            Money.of(
                                            base.unitPrice()
                                                    .amount()
                                                    .multiply(unit.get().qtyInBase()),
                                            data.currency())
                                    .rounded(data.rounding()),
                            DERIVED));
        }
        if (found.isEmpty()) {
            return ResolvedLine.unpriced(line, ResolvedLine.Status.PRICE_NOT_FOUND);
        }
        return priced(request, line, variant, qtyBase, found.get(), data);
    }

    private static ResolvedLine priced(
            ResolutionRequest request,
            ResolutionRequest.Line line,
            VariantRef variant,
            BigDecimal qtyBase,
            Found found,
            PricingData data) {
        RuleContext context = new RuleContext(
                request.channel(),
                found.list().id(),
                request.customerGroupId(),
                request.customerId(),
                variant.productId(),
                variant.variantId(),
                line.packagingUnitId(),
                qtyBase);
        BigDecimal price = found.unitPrice().amount();
        String source = found.source();
        List<String> applied = new ArrayList<>();
        boolean promotionApplied = false;
        List<PriceRule> rules = data.rules().stream()
                .filter(r -> r.appliesAt(request.at()) && r.conditions().matches(context))
                .sorted(Comparator.comparingInt(PriceRule::priority).thenComparing(PriceRule::code))
                .toList();
        for (PriceRule rule : rules) {
            if (rule.kind() == RuleKind.PROMOTION) {
                // Rule PRC-06: one promotion per line unless the later one is stackable.
                if (promotionApplied && !rule.stackable()) {
                    continue;
                }
                promotionApplied = true;
            }
            price = rule.effect().apply(price);
            applied.add(rule.code());
            source = RULE + rule.code();
        }
        Money listAmount = found.unitPrice().times(line.qty()).rounded(data.rounding());
        Money amount = Money.of(price.multiply(line.qty()), data.currency()).rounded(data.rounding());
        List<TaxRate> rates = variant.taxCategoryId() == null
                ? List.of()
                : data.taxRates().stream()
                        .filter(r -> r.taxCategoryId().equals(variant.taxCategoryId()))
                        .filter(r -> r.appliesOn(request.businessDate()))
                        .toList();
        TaxCalculator.Split split = TaxCalculator.split(amount, found.list().taxInclusive(), rates, data.rounding());
        return new ResolvedLine(
                line.lineId(),
                line.variantId(),
                line.packagingUnitId(),
                line.qty(),
                ResolvedLine.Status.PRICED,
                found.unitPrice(),
                amount,
                listAmount.minus(amount),
                split.net(),
                split.tax(),
                split.total(),
                found.list().taxInclusive(),
                found.list().id(),
                found.list().currentVersion(),
                source,
                applied,
                split.taxes());
    }

    /** The first candidate list with a valid price for the unit, and its row with the highest minQty within reach. */
    private static Optional<Found> listPrice(
            List<PriceList> candidates, List<Price> prices, UUID unitId, BigDecimal qty, ResolutionRequest request) {
        for (PriceList list : candidates) {
            Optional<Price> row = prices.stream()
                    .filter(p -> p.priceListId().equals(list.id())
                            && p.packagingUnitId().equals(unitId))
                    .filter(p -> p.validAt(request.at()) && p.minQty().compareTo(qty) <= 0)
                    .max(Comparator.comparing(Price::minQty).thenComparing(Price::validFrom));
            if (row.isPresent()) {
                return Optional.of(new Found(list, Money.of(row.get().amount(), currency(list)), LIST));
            }
        }
        return Optional.empty();
    }

    private static CurrencyCode currency(PriceList list) {
        return CurrencyCode.of(list.currency());
    }

    /** A price found in a list. */
    private record Found(PriceList list, Money unitPrice, String source) {}
}
