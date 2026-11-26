package com.bmp.sales.application;

import com.bmp.pricing.api.PricingQueries;
import com.bmp.pricing.api.dto.BasketQuote;
import com.bmp.pricing.api.dto.QuotedLine;
import com.bmp.sales.domain.error.SalesErrorCode;
import com.bmp.sales.domain.model.BillFlag;
import com.bmp.sales.domain.model.LineDraft;
import com.bmp.sales.domain.model.Mode;
import com.bmp.sales.domain.model.SalesChannel;
import com.bmp.sales.domain.port.CustomerStore;
import com.bmp.sales.domain.port.SaleItemStore;
import com.bmp.sales.domain.service.WeighedQuantity;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.money.TaxSplit;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Rules SAL-03, SAL-08, SAL-15: turns the till's lines into priced lines from the variant replica (base quantity, stock
 * state, name) and pricing (unit price, rule discount, tax rates). Online a price that differs from pricing is refused
 * unless a manager overrode it; offline the till's price stands and the bill is flagged.
 */
@Component
public class LinePricer {

    private static final Logger log = LoggerFactory.getLogger(LinePricer.class);
    private static final int QTY_SCALE = 6;

    private final SaleItemStore items;
    private final PricingQueries pricing;
    private final Approvals approvals;

    public LinePricer(SaleItemStore items, PricingQueries pricing, Approvals approvals) {
        this.items = items;
        this.pricing = pricing;
        this.approvals = approvals;
    }

    /** A manager's price for a line (SAL-03). */
    public record PriceOverride(
            BigDecimal unitPrice, UUID approvedBy, @Nullable String reason) {}

    /**
     * A line as the till sent it.
     *
     * @param unitPrice the price the till shows; required offline
     * @param lineDiscount the cashier's discount
     * @param ruleDiscount what price rules took off at the till (offline)
     */
    public record LineInput(
            UUID id,
            UUID variantId,
            UUID packagingUnitId,
            BigDecimal qty,
            boolean weighed,
            @Nullable BigDecimal unitPrice,
            @Nullable BigDecimal lineDiscount,
            @Nullable BigDecimal ruleDiscount,
            @Nullable UUID discountApprovedBy,
            @Nullable PriceOverride override) {}

    /** The priced lines and what pricing found. */
    public record Priced(
            List<LineDraft> lines,
            Set<BillFlag> flags,
            @Nullable UUID priceListId,
            @Nullable Integer priceListVersion,
            boolean discountApproved) {}

    public Priced price(
            List<LineInput> inputs,
            SalesChannel channel,
            CustomerStore.@Nullable Customer customer,
            Instant at,
            Mode mode) {
        List<BasketQuote.Line> quoteLines = new ArrayList<>();
        List<SaleItemStore.SaleItem> sold = new ArrayList<>();
        List<SaleItemStore.Unit> units = new ArrayList<>();
        for (LineInput in : inputs) {
            SaleItemStore.SaleItem item = items.find(in.variantId())
                    .filter(SaleItemStore.SaleItem::active)
                    .orElseThrow(() -> new BusinessRuleViolation(
                            SalesErrorCode.UNKNOWN_VARIANT, Map.of("variantId", in.variantId())));
            SaleItemStore.Unit unit = item.unit(in.packagingUnitId())
                    .orElseThrow(() -> new BusinessRuleViolation(
                            SalesErrorCode.UNKNOWN_PACKAGING_UNIT, Map.of("packagingUnitId", in.packagingUnitId())));
            if (!unit.canSell() || !unit.active()) {
                throw new BusinessRuleViolation(
                        SalesErrorCode.UNIT_NOT_SELLABLE, Map.of("packagingUnitId", in.packagingUnitId()));
            }
            sold.add(item);
            units.add(unit);
            quoteLines.add(new BasketQuote.Line(in.id().toString(), item.variantId(), unit.id(), in.qty()));
        }
        List<QuotedLine> quotes = pricing.quote(new BasketQuote(
                channel.name(),
                customer == null ? null : customer.partyId(),
                customer == null ? null : customer.customerGroupId(),
                at,
                quoteLines));
        Set<BillFlag> flags = EnumSet.noneOf(BillFlag.class);
        List<LineDraft> drafts = new ArrayList<>();
        UUID priceListId = null;
        Integer priceListVersion = null;
        boolean discountApproved = false;
        for (int i = 0; i < inputs.size(); i++) {
            LineInput in = inputs.get(i);
            SaleItemStore.SaleItem item = sold.get(i);
            SaleItemStore.Unit unit = units.get(i);
            QuotedLine quote = quotes.get(i);
            BigDecimal qtyBase = in.qty().multiply(unit.qtyInBase()).setScale(QTY_SCALE, RoundingMode.HALF_UP);
            WeighedQuantity.check(in.id(), in.weighed(), item.weighable(), in.qty(), qtyBase, item.baseUnit());
            Price price = price(in, quote, mode, flags);
            if (quote.priced() && priceListId == null) {
                priceListId = quote.priceListId();
                priceListVersion = quote.priceListVersion();
            }
            BigDecimal cashierDiscount = in.lineDiscount() == null ? BigDecimal.ZERO : in.lineDiscount();
            if (cashierDiscount.signum() > 0 && in.discountApprovedBy() != null) {
                discountApproved |= approvals.approved("sales:discount-unlimited", in.discountApprovedBy(), mode);
            }
            drafts.add(new LineDraft(
                    in.id(),
                    item.variantId(),
                    unit.id(),
                    unit.isBase() ? item.name() : item.name() + " (" + unit.name() + ")",
                    in.qty(),
                    qtyBase,
                    unit.stockState(),
                    in.weighed(),
                    price.unitPrice(),
                    cashierDiscount.add(price.ruleDiscount()),
                    price.ruleDiscount(),
                    price.source(),
                    quote.priced() ? quote.taxInclusive() : true,
                    quote.rates().stream()
                            .map(r -> new TaxSplit.Rate(r.id(), r.code(), r.ratePct(), r.compound()))
                            .toList()));
        }
        return new Priced(drafts, flags, priceListId, priceListVersion, discountApproved);
    }

    private record Price(
            BigDecimal unitPrice,
            BigDecimal ruleDiscount,
            @Nullable String source) {}

    private Price price(LineInput in, QuotedLine quote, Mode mode, Set<BillFlag> flags) {
        if (in.override() != null) {
            PriceOverride override = in.override();
            if (!approvals.approved("sales:price-override", override.approvedBy(), mode)) {
                if (mode.online()) {
                    throw new BusinessRuleViolation(
                            SalesErrorCode.PRICE_OVERRIDE_NOT_ALLOWED, Map.of("lineId", in.id()));
                }
                flags.add(BillFlag.PRICE_MISMATCH);
            }
            return new Price(override.unitPrice(), BigDecimal.ZERO, "OVERRIDE");
        }
        if (mode.online()) {
            if (!quote.priced() || quote.unitPrice() == null) {
                throw new BusinessRuleViolation(SalesErrorCode.PRICE_NOT_FOUND, Map.of("lineId", in.id()));
            }
            BigDecimal quoted = quote.unitPrice();
            if (in.unitPrice() != null && in.unitPrice().compareTo(quoted) != 0) {
                throw new BusinessRuleViolation(
                        SalesErrorCode.PRICE_MISMATCH, Map.of("lineId", in.id(), "price", quoted.toPlainString()));
            }
            return new Price(quoted, quote.ruleDiscount(), quote.source());
        }
        // Offline: the customer paid the till's price; a difference from the stated price list is flagged.
        BigDecimal tillPrice = in.unitPrice() != null ? in.unitPrice() : BigDecimal.ZERO;
        BigDecimal tillRule = in.ruleDiscount() != null ? in.ruleDiscount() : BigDecimal.ZERO;
        boolean matches = quote.priced()
                && quote.unitPrice() != null
                && quote.unitPrice().compareTo(tillPrice) == 0
                && quote.ruleDiscount().compareTo(tillRule) == 0;
        if (!matches) {
            flags.add(BillFlag.PRICE_MISMATCH);
            log.atWarn()
                    .addKeyValue("lineId", in.id())
                    .addKeyValue("variantId", in.variantId())
                    .log("Offline bill line priced differently from the price list");
        }
        return new Price(tillPrice, tillRule, quote.source());
    }
}
